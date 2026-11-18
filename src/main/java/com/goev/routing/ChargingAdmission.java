package com.goev.routing;

import com.goev.ev.*;
import com.goev.evcs.*;
import com.goev.state.*;
import com.goev.mqtt.ChargeRequestMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Persistent atomic legacy charging admission. Capacity counts commitments, never modifies heartbeats. */
@Service
public class ChargingAdmission {
    private final EvRepository evs;private final EvcsRepository stations;private final AssignmentRepository assignments;
    private final StateService state;private final RoutingProperties properties;
    private final ChargeRequestRepository requests;
    public ChargingAdmission(EvRepository evs,EvcsRepository stations,AssignmentRepository assignments,StateService state,RoutingProperties properties,ChargeRequestRepository requests){
        this.evs=evs;this.stations=stations;this.assignments=assignments;this.state=state;this.properties=properties;
        this.requests=requests;
    }
    public static boolean active(Assignment a,Instant now){return (a.getStatus()==AssignmentStatus.RESERVED||a.getStatus()==AssignmentStatus.CONFIRMED)&&a.getExpiresAt().isAfter(now);}
    @Transactional
    public Optional<Assignment> reserve(ChargeRequestMessage request,RoutingCandidate candidate,String reason) {
        Instant now=Instant.now();
        var requestRow=requests.lockById(request.requestId()).orElse(null);
        if(requestRow==null || !Objects.equals(requestRow.getEvId(),request.evId()))return Optional.empty();
        Ev ev=evs.lockById(request.evId()).orElse(null);if(ev==null)return Optional.empty();
        var previous=assignments.findByRequestId(request.requestId());
        if(!previous.isEmpty())return previous.stream().filter(a->Objects.equals(a.getEvId(),request.evId())).findFirst();
        if(assignments.findByEvId(request.evId()).stream().anyMatch(a->active(a,now)))return Optional.empty();
        Evcs station=stations.lockById(candidate.evcs().getEvcsId()).orElse(null);
        if(station==null || station.getTotalSlots()==null || station.getTotalSlots()<1)return Optional.empty();
        var live=state.getReportedEvcsState(station.getEvcsId()).orElse(null);
        if(live==null || live.getAvailableSlots()==null || live.getAvailableSlots()<0 || live.getAvailableSlots()>station.getTotalSlots()
                || live.getCurrentLoadKw()==null || !Double.isFinite(live.getCurrentLoadKw()) || live.getCurrentLoadKw()<0
                || live.getTtlSeconds()==null || live.getLastHeartbeatAt()==null
                || live.getLastHeartbeatAt().isBefore(now.minusSeconds(live.getTtlSeconds())))return Optional.empty();
        var occupied=assignments.findByEvcsId(station.getEvcsId()).stream().filter(a->active(a,now)).toList();
        // Reported availability includes device-known reservations. Subtract those identities before
        // adding authoritative commitments. Older identity-free payloads are admitted conservatively.
        long reportedOwn=live.getCommittedReservationIds()==null?0:occupied.stream()
                .filter(a->live.getCommittedReservationIds().contains(a.getAssignmentId().toString())).count();
        long external=Math.max(0,station.getTotalSlots()-live.getAvailableSlots()-reportedOwn);
        if(occupied.size()+external>=station.getTotalSlots())return Optional.empty();
        double capacity=request.batteryCapacityKwh()!=null?request.batteryCapacityKwh():ev.getBatteryCapacityKwh()==null?0:ev.getBatteryCapacityKwh();
        if(capacity<=0 || request.socPercent()==null || !Double.isFinite(capacity) || !Double.isFinite(request.socPercent())
                || request.socPercent()<0 || request.socPercent()>100 || !Double.isFinite(candidate.distanceKm()) || candidate.distanceKm()<0)return Optional.empty();
        // Legacy capacity interpreted as usable until a nominal/usable field is added; SOH is not applied twice.
        double consumption=ev.getEnergyConsumptionKwhPerKm()==null?properties.getDefaultEnergyConsumptionKwhPerKm():ev.getEnergyConsumptionKwhPerKm();
        double arrivalSoc=request.socPercent()-100*consumption*candidate.distanceKm()/capacity;
        double reserve=ev.getMinSocThresholdPercent()==null?properties.getDefaultMinSocThresholdPercent():ev.getMinSocThresholdPercent();
        if(!Double.isFinite(consumption)||consumption<0||!Double.isFinite(arrivalSoc)||arrivalSoc<reserve)return Optional.empty();
        double target=ev.getTargetSocPercent()==null?80:ev.getTargetSocPercent();
        double power=live.getChargeRateKw()==null?0:live.getChargeRateKw();
        if(ev.getMaxChargeRateKw()!=null)power=Math.min(power,ev.getMaxChargeRateKw());
        if(station.getRatedCapacityKw()!=null)power=Math.min(power,station.getRatedCapacityKw()-occupied.stream().mapToDouble(Assignment::getPlannedChargeKw).sum());
        if(!Double.isFinite(power)||power<=0||target>100||target<=arrivalSoc)return Optional.empty();
        int eta=(int)Math.ceil(candidate.distanceKm()/properties.getAssumedAvgSpeedKmh()*60);
        long chargingSeconds=Math.max(1,(long)Math.ceil(capacity*(target-arrivalSoc)/100/power*3600));
        Instant arrival=now.plusSeconds(eta*60L);
        Assignment assignment=new Assignment(UUID.randomUUID(),request.requestId(),station.getEvcsId(),now,
                arrival.plusSeconds(chargingSeconds),AssignmentStatus.RESERVED);
        assignment.planned(request.evId(),candidate.distanceKm(),eta,power,arrival,reason);
        return Optional.of(assignments.save(assignment));
    }
    @Transactional
    public void reconcile() {
        Instant now=Instant.now();
        for(Assignment row:assignments.findAll()) {
            Assignment a=assignments.lockById(row.getAssignmentId()).orElseThrow();
            if(a.getStatus()!=AssignmentStatus.RESERVED&&a.getStatus()!=AssignmentStatus.CONFIRMED)continue;
            if(!a.getExpiresAt().isAfter(now)){a.complete();assignments.save(a);}
            else if(a.getArrivalAt()!=null && !a.getArrivalAt().isAfter(now) && a.getStatus()==AssignmentStatus.RESERVED){a.confirm();assignments.save(a);}
        }
    }
}
