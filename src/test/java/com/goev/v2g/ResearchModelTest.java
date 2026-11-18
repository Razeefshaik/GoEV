package com.goev.v2g;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.goev.v2g.Model.*;
import static org.assertj.core.api.Assertions.*;

class ResearchModelTest {
    private final ScenarioCatalog catalog=new ScenarioCatalog();
    private final ResearchEngine engine=new ResearchEngine();
    private Settings settings(double cap){return new Settings(11,1,.95,1.05,cap,100,0.9,30,3,.25,.1,1e-10,200,1);}
    @Test void referenceBatteryAndZeroPower() {
        Ev e=new Ev("reference",1,60,70,20,.2,10,true,true,"CCS",4,50,0,0);
        var b=new BatteryModel().budget(e,0,.9,10);
        assertThat(b.batteryKwh()).isEqualTo(30);assertThat(b.gridKwh()).isEqualTo(27);assertThat(b.maxHours()).isEqualTo(2.7);
        assertThat(new BatteryModel().budget(e,0,.9,0).maxHours()).isZero();
        assertThatThrownBy(()->new BatteryModel().budget(e,200,.9,10)).hasMessageContaining("reserve");
        assertThatThrownBy(()->new BatteryModel().budget(e,0,1.1,10)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oneBranchMatchesAnalyticalHighVoltageRoot() {
        double r=1,p=10000;var solver=new RadialPowerFlow(List.of(new Branch(1,1,2,r,0,1,.01,1)));
        Flow f=solver.solve(settings(1000),1,Map.of());
        double base=11000/Math.sqrt(3),voltage=(base+Math.sqrt(base*base-4*r*p/3))/2;
        assertThat(f.converged()).isTrue();assertThat(f.buses().get(1).voltagePu()).isCloseTo(voltage/base,within(1e-9));
        assertThat(f.lossesKw()).isCloseTo(3*Math.pow(p/3/voltage,2)*r/1000,within(1e-7));
        assertThat(f.balanceResidualKw()).isCloseTo(0,within(1e-6));
        Flow zero=solver.solve(settings(1000),0,Map.of());assertThat(zero.sourceKw()).isZero();assertThat(zero.lossesKw()).isZero();
    }
    @Test void reverseFlowAndNonConvergenceCannotBeAccepted() {
        var solver=new RadialPowerFlow(List.of(new Branch(1,1,2,1,.5,1,.01,1)));
        Flow reverse=solver.solve(settings(1000),1,Map.of(2,30.0));assertThat(reverse.sourceKw()).isNegative();
        assertThat(reverse.lines().getFirst().direction()).isEqualTo("REVERSE");
        Settings limited=new Settings(11,1,.95,1.05,1000,0,.9,30,3,.25,.1,1e-15,1,1);
        Flow failed=solver.solve(limited,1,Map.of());assertThat(failed.converged()).isFalse();assertThat(solver.feasible(failed,limited)).isFalse();
    }
    @Test void topologyAndOffsetsRejected() {
        assertThatThrownBy(()->new RadialPowerFlow(List.of(new Branch(1,2,3,1,1,1,1,1))))
            .hasMessageContaining("disconnected");
        assertThatThrownBy(()->new RadialPowerFlow(List.of(new Branch(1,1,2,1,1,1,1,1),new Branch(2,3,2,1,1,1,1,1))))
            .hasMessageContaining("parent");
        assertThatThrownBy(()->new TravelModel.FeederSurrogate(catalog.load("no-shortage").branches()).distanceKm(1,2,1))
            .hasMessageContaining("offset");
    }
    @Test void weaselVersionPreservesImpedancesAndDownstreamDemand() {
        Scenario s=catalog.load("moderate-shortage");
        assertThat(s.branches().stream().mapToDouble(Branch::lengthKm).sum()).isCloseTo(34.923/.9291,within(1e-10));
        assertThat(s.branches().stream().mapToDouble(Branch::peakMva).sum()).isCloseTo(1.082,within(1e-12));
        Flow f=new RadialPowerFlow(s.branches()).solve(s.settings(),1,Map.of());
        assertThat(f.lines().getFirst().pKw()).isGreaterThan(1027.9); // Includes every downstream demand and losses.
    }
    @Test void noShortageDoesNotSendVehicles() {
        Run run=engine.start(catalog.load("no-shortage"),"coordinated","test");assertThat(run.sessions).isEmpty();
    }
    @Test void arrivalEnergyReserveCombinedElectricalAndSiteLimits() {
        Run r=engine.start(catalog.load("moderate-shortage"),"coordinated","test");
        assertThat(r.sessions).isNotEmpty();double earliest=r.sessions.stream().mapToDouble(s->s.etaHour).min().orElseThrow();
        engine.advance(r,earliest/2,true);assertThat(r.samples).allSatisfy(s->assertThat(s.evKw()).isZero());
        engine.advance(r,3,true);
        for(Session s:r.sessions) {
            assertThat(s.currentSoc).isGreaterThanOrEqualTo(s.reserveSoc);
            assertThat(s.deliveredKwh).isLessThanOrEqualTo(s.deliverableGridKwh+1e-8);
            assertThat(s.batteryUsedKwh*.9).isCloseTo(s.deliveredKwh,within(1e-8));
            assertThat(s.history).contains(Status.RESERVED,Status.EN_ROUTE,Status.ARRIVED,Status.DISCHARGING,Status.COMPLETED);
        }
        for(Sample sample:r.samples) {
            assertThat(sample.v2g().flow().converged()).isTrue();assertThat(sample.v2g().flow().voltageViolations()).isZero();
            assertThat(sample.v2g().flow().loadingViolations()).isZero();assertThat(sample.v2g().flow().sourceKw()).isLessThanOrEqualTo(550.00001);
            assertThat(Math.abs(sample.v2g().flow().balanceResidualKw())).isLessThan(.001);
        }
        var m=engine.metrics(r,true);var baseline=engine.metrics(r,false);
        assertThat(m.requestedKwh()).isEqualTo(baseline.requestedKwh());assertThat(m.unservedKwh()).isLessThan(baseline.unservedKwh());
        for(Station station:r.scenario.stations())assertThat(r.sessions.stream().filter(s->s.stationId.equals(station.id())).mapToDouble(s->s.assignedKw).sum()).isLessThanOrEqualTo(station.siteKw());
    }
    @Test void eligibilityFailuresDeadlineDepletionAndStaleRuntime() {
        for(String id:List.of("unwilling","incompatible","travel-reserve","stale-telemetry")) {
            Run r=engine.start(catalog.load(id),"coordinated",id);assertThat(r.sessions).isEmpty();assertThat(r.rejections).isNotEmpty();
        }
        for(String id:List.of("energy-depletion","departure")) {
            Run r=engine.start(catalog.load(id),"coordinated",id);engine.advance(r,3,true);
            assertThat(r.sessions).isNotEmpty();assertThat(r.sessions).allSatisfy(s->assertThat(s.endHour).isLessThan(1));
            assertThat(r.samples.stream().filter(s->s.hour()>1).mapToDouble(Sample::evKw).sum()).isZero();
        }
        Run r=engine.start(catalog.load("moderate-shortage"),"coordinated","stale");engine.advance(r,.3,false);
        assertThat(r.sessions).allSatisfy(s->assertThat(s.status).isEqualTo(Status.FAILED));
    }
    @Test void lifecycleIdempotentAndIllegalTransitionsRejected() {
        Session s=new Session();s.transition(Status.RESERVED);s.transition(Status.RESERVED);
        assertThat(s.history).hasSize(2);assertThatThrownBy(()->s.transition(Status.DISCHARGING)).hasMessageContaining("invalid");
        s.transition(Status.CANCELLED);assertThatThrownBy(()->s.transition(Status.EN_ROUTE)).hasMessageContaining("terminal");
    }
}
