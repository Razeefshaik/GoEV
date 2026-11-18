package com.goev.v2g;

import java.time.Instant;
import java.util.*;
import static com.goev.v2g.Model.*;

/** Pure deterministic simulation; all clock movement is explicit and battery data are simulated. */
public final class ResearchEngine {
    private final ScenarioCatalog catalog=new ScenarioCatalog();
    private final BatteryModel battery=new BatteryModel();
    public Run start(Scenario scenario,String policy,String requestId) {
        catalog.validate(scenario);
        if(!Set.of("coordinated","nearest","none").contains(policy))throw new IllegalArgumentException("unknown policy");
        Run run=new Run();run.id=UUID.randomUUID().toString();run.requestId=requestId;run.createdAt=Instant.now().toString();
        run.policy=policy;run.scenario=scenario;
        run.fullDemandBaseline=new RadialPowerFlow(scenario.branches()).solve(scenario.settings(),1,Map.of());
        DispatchPolicy dispatch=new DispatchPolicy(scenario);run.currentBaseline=dispatch.evaluate(Map.of());run.currentV2g=run.currentBaseline;
        if(!policy.equals("none") && run.currentBaseline.unservedKw()>0.001) assign(run,dispatch);
        return run;
    }
    private record Candidate(Ev ev,Station station,BatteryModel.Budget budget,double distance,double power,
                             double eta,double duration,double marginalServed) {}
    private void assign(Run run,DispatchPolicy dispatch) {
        Scenario sc=run.scenario;Settings cfg=sc.settings();TravelModel travel=new TravelModel.FeederSurrogate(sc.branches());
        Set<String> assigned=new HashSet<>();Map<Integer,Double> committed=new TreeMap<>();
        while(true) {
            List<Candidate> candidates=new ArrayList<>();
            Dispatch previous=dispatch.evaluate(committed);
            for(Ev ev:sc.fleet()) if(!assigned.contains(ev.id())) for(Station station:sc.stations()) {
                try {
                    if(!ev.willing())throw new IllegalArgumentException("owner unwilling");
                    if(!ev.bidirectional() || !station.bidirectional())throw new IllegalArgumentException("bidirectional equipment required");
                    if(!station.connectors().contains(ev.connector()))throw new IllegalArgumentException("connector incompatible");
                    if(!station.online() || !fresh(station.telemetryHour(),0,cfg) || !fresh(ev.telemetryHour(),0,cfg))
                        throw new IllegalArgumentException("offline/stale telemetry");
                    long ports=run.sessions.stream().filter(s->s.stationId.equals(station.id()) && !s.terminal()).count();
                    if(ports+station.externalOccupiedPorts()>=station.totalPorts())throw new IllegalArgumentException("no authoritative port available");
                    double used=run.sessions.stream().filter(s->s.stationId.equals(station.id()) && !s.terminal()).mapToDouble(s->s.assignedKw).sum();
                    double distance=travel.distanceKm(ev.bus(),station.bus(),0);
                    if(distance>ev.maxTravelKm())throw new IllegalArgumentException("maximum travel distance exceeded");
                    double eta=distance/cfg.speedKmH();
                    double power=Math.min(ev.maxGridKw(),Math.min(station.portKw(),station.siteKw()-used));
                    power=Math.min(power,Math.max(0,previous.unservedKw()));
                    if(power<0.001)throw new IllegalArgumentException("site/support power exhausted");
                    var budget=battery.budget(ev,distance,cfg.efficiency(),power);
                    double duration=Math.min(budget.maxHours(),Math.min(cfg.horizonHours(),ev.departureHour())-eta);
                    if(duration<1.0/60)throw new IllegalArgumentException("insufficient energy/departure time after arrival");
                    Map<Integer,Double> next=new TreeMap<>(committed);next.merge(station.bus(),power,Double::sum);
                    Dispatch projected=dispatch.evaluate(next);
                    double gain=projected.servedKw()-previous.servedKw();
                    if(gain<0.001)throw new IllegalArgumentException("no feasible electrical service benefit");
                    candidates.add(new Candidate(ev,station,budget,distance,power,eta,duration,gain));
                } catch(IllegalArgumentException e) {
                    String reason=ev.id()+" / "+station.id()+": "+e.getMessage();
                    if(!run.rejections.contains(reason))run.rejections.add(reason);
                }
            }
            if(candidates.isEmpty())break;
            Comparator<Candidate> comparator=run.policy.equals("nearest")?
                Comparator.comparingDouble(Candidate::distance):
                Comparator.<Candidate>comparingDouble(c->-c.marginalServed()*c.duration()).thenComparingDouble(Candidate::distance);
            Candidate chosen=candidates.stream().min(comparator.thenComparing(c->c.ev().id()).thenComparing(c->c.station().id())).orElseThrow();
            Session s=new Session();s.id=run.id+":"+chosen.ev.id();s.evId=chosen.ev.id();s.stationId=chosen.station.id();s.bus=chosen.station.bus();
            s.distanceKm=chosen.distance;s.etaHour=chosen.eta;s.arrivalSoc=chosen.budget.arrivalSoc();s.reserveSoc=chosen.ev.reserveSoc();
            s.availableBatteryKwh=chosen.budget.batteryKwh();s.deliverableGridKwh=chosen.budget.gridKwh();s.assignedKw=chosen.power;
            s.maxDurationHours=chosen.budget.maxHours();s.scheduledDurationHours=chosen.duration;s.startHour=chosen.eta;s.endHour=chosen.eta+chosen.duration;
            s.remainingHours=chosen.duration;s.currentSoc=chosen.ev.soc();s.travelKwh=chosen.budget.travelKwh();
            s.expectedFinalSoc=s.arrivalSoc-100*s.assignedKw*s.scheduledDurationHours/(cfg.efficiency()*chosen.ev.usableKwh());
            s.reason=run.policy+" heuristic; feasible combined injection; proportional service objective";
            s.transition(Status.RESERVED);s.transition(Status.EN_ROUTE);run.sessions.add(s);command(run,s,"RESERVE",s.assignedKw);
            assigned.add(s.evId);committed.merge(s.bus,s.assignedKw,Double::sum);
        }
    }
    public static boolean fresh(double stamp,double hour,Settings cfg) {
        return stamp<=hour+1e-9 && hour-stamp<=cfg.freshnessHours()+1e-9;
    }
    public void advance(Run run,double hours,boolean simulatedHeartbeat) {
        positive(hours,"advance hours");if(hours>48)throw new IllegalArgumentException("advance >48 h");
        double target=Math.min(run.scenario.settings().horizonHours(),run.hour+hours);
        Settings cfg=run.scenario.settings();DispatchPolicy dispatch=new DispatchPolicy(run.scenario);
        while(run.hour<target-1e-9) {
            double end=Math.min(target,run.hour+cfg.stepHours());
            // Split at every arrival/end event; never inject during a travel interval.
            for(Session s:run.sessions) if(!s.terminal()) {
                if(s.startHour>run.hour+1e-9)end=Math.min(end,s.startHour);
                if(s.endHour>run.hour+1e-9)end=Math.min(end,s.endHour);
            }
            Map<Integer,Double> active=new TreeMap<>();
            for(Session s:run.sessions) {
                if(s.terminal())continue;
                if(run.hour>=s.endHour-1e-9) {finish(run,s,Status.COMPLETED,"scheduled support ended");continue;}
                Station station=station(run,s.stationId);
                if(!simulatedHeartbeat && (!station.online() || !fresh(station.telemetryHour(),end,cfg))) {
                    finish(run,s,Status.FAILED,"station telemetry stale; fail closed");continue;
                }
                if(s.status==Status.EN_ROUTE && run.hour>=s.startHour-1e-9) {
                    s.currentSoc=s.arrivalSoc;s.transition(Status.ARRIVED);s.transition(Status.DISCHARGING);command(run,s,"START",s.assignedKw);
                }
                if(s.status==Status.DISCHARGING) {
                    double remaining=Math.max(0,s.deliverableGridKwh-s.deliveredKwh);
                    if(remaining<1e-8){finish(run,s,Status.COMPLETED,"energy depleted at reserve");continue;}
                    end=Math.min(end,run.hour+remaining/s.assignedKw);
                    active.merge(s.bus,s.assignedKw,Double::sum);
                }
            }
            double dt=end-run.hour;
            if(dt<=1e-10)throw new IllegalStateException("invalid event time step");
            Dispatch baseline=dispatch.evaluate(Map.of()),with=dispatch.evaluate(active);
            double grid=0,batteryUsed=0;
            for(Session s:run.sessions) if(s.status==Status.DISCHARGING) {
                Ev ev=ev(run,s.evId);double power=s.assignedKw*with.injectionScale();
                s.actualKw=power;double energy=Math.min(power*dt,Math.max(0,s.deliverableGridKwh-s.deliveredKwh));
                s.deliveredKwh+=energy;s.batteryUsedKwh+=energy/cfg.efficiency();grid+=energy;batteryUsed+=energy/cfg.efficiency();
                s.currentSoc=Math.max(s.reserveSoc+100*ev.onwardKwh()/ev.usableKwh(),s.arrivalSoc-100*s.batteryUsedKwh/ev.usableKwh());
                s.remainingHours=Math.max(0,Math.min(s.endHour-end,power==0?0:(s.deliverableGridKwh-s.deliveredKwh)/power));
                s.expectedFinalSoc=Math.max(s.reserveSoc+100*ev.onwardKwh()/ev.usableKwh(),s.currentSoc-100*power*s.remainingHours/(cfg.efficiency()*ev.usableKwh()));
                command(run,s,"SETPOINT",power);
                run.outbox.getLast().simulationHour=end;
            }
            for(Session s:run.sessions)if(s.status==Status.EN_ROUTE) {
                Ev ev=ev(run,s.evId);
                double travelFraction=s.etaHour==0?1:Math.min(1,end/s.etaHour);
                s.currentSoc=ev.soc()-100*s.travelKwh*travelFraction/ev.usableKwh();
            }
            run.samples.add(new Sample(run.hour,dt,baseline,with,dt==0?0:grid/dt,grid,batteryUsed));
            run.currentBaseline=baseline;run.currentV2g=with;run.hour=end;
            for(Session s:run.sessions) if(!s.terminal() && (run.hour>=s.endHour-1e-9 || s.deliveredKwh>=s.deliverableGridKwh-1e-8))
                finish(run,s,Status.COMPLETED,"scheduled end/energy reserve reached");
        }
        refresh(run);
    }
    public void cancel(Run run,String sessionId) {
        Session session=run.sessions.stream().filter(s->s.id.equals(sessionId)).findFirst().orElseThrow(()->new IllegalArgumentException("unknown session"));
        if(!session.terminal())finish(run,session,Status.CANCELLED,"owner cancelled");
        refresh(run);
    }
    public void fail(Run run,String sessionId,String reason) {
        Session s=run.sessions.stream().filter(x->x.id.equals(sessionId)).findFirst().orElseThrow();
        if(!s.terminal())finish(run,s,Status.FAILED,reason);refresh(run);
    }
    public void refresh(Run run) {
        DispatchPolicy dispatch=new DispatchPolicy(run.scenario);Map<Integer,Double> present=new TreeMap<>();
        for(Session s:run.sessions)if(s.status==Status.DISCHARGING)present.merge(s.bus,s.assignedKw,Double::sum);
        run.currentBaseline=dispatch.evaluate(Map.of());run.currentV2g=dispatch.evaluate(present);
        for(Session s:run.sessions)s.actualKw=s.status==Status.DISCHARGING?s.assignedKw*run.currentV2g.injectionScale():0;
    }
    private void finish(Run run,Session s,Status status,String reason) {
        s.transition(status);s.actualKw=0;s.remainingHours=0;s.actualStopHour=run.hour;s.reason=reason;s.expectedFinalSoc=s.currentSoc;
        command(run,s,"STOP",0);
    }
    private void command(Run run,Session s,String type,double power) {
        // Each persisted command is immutable; retry uses the same ID.
        Command c=new Command();c.commandId=UUID.randomUUID().toString();c.sessionId=s.id;c.stationId=s.stationId;c.type=type;c.powerKw=power;
        c.simulationHour=run.hour;c.simulatedSoc=s.currentSoc;c.simulatedDeliveredKwh=s.deliveredKwh;run.outbox.add(c);
        c.sequence=run.outbox.size();
    }
    private Ev ev(Run r,String id){return r.scenario.fleet().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();}
    private Station station(Run r,String id){return r.scenario.stations().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();}
    public Metrics metrics(Run run,boolean v2g) {
        double requested=0,served=0,loss=0,source=0,delivered=0,used=0,shortage=0,min=1,max=0,residual=0;
        for(Sample s:run.samples) {
            Dispatch d=v2g?s.v2g():s.baseline();double dt=s.durationHours();
            requested+=d.requestedKw()*dt;served+=d.servedKw()*dt;loss+=d.flow().lossesKw()*dt;source+=d.flow().sourceKw()*dt;
            if(v2g){delivered+=s.deliveredKwh();used+=s.batteryUsedKwh();}
            if(d.unservedKw()>0.001)shortage+=dt;min=Math.min(min,d.flow().minVoltagePu());max=Math.max(max,d.flow().maxLoadingPercent());
            residual=Math.max(residual,Math.abs(d.flow().balanceResidualKw()));
        }
        double travel=v2g?run.sessions.stream().mapToDouble(s->s.travelKwh).sum():0;
        return new Metrics(requested,served,requested-served,loss,source,delivered,used,travel,shortage,min,max,residual,
            v2g?run.sessions.size():0,v2g?run.rejections.size():0);
    }
}
