package com.goev.routing;

import com.goev.ev.*;
import com.goev.evcs.*;
import com.goev.state.*;
import com.goev.mqtt.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({ChargingAdmission.class,ChargingAdmissionTest.Config.class})
class ChargingAdmissionTest {
    @org.springframework.boot.test.context.TestConfiguration static class Config {@Bean RoutingProperties properties(){return new RoutingProperties();}}
    @Autowired ChargingAdmission admission;
    @Autowired EvRepository evs;
    @Autowired EvcsRepository stations;
    @Autowired ChargeRequestRepository requests;
    @Autowired AssignmentRepository assignments;
    @MockBean StateService state;
    Evcs station;
    @BeforeEach void setup() {
        station=stations.save(new Evcs("ST",2,1,10.0,"ccs",20.0,0.0));
        for(String id:List.of("A","B"))evs.save(new Ev(id,"ccs",60.0,null,null,20.0,80.0,.2,10.0));
        when(state.getReportedEvcsState("ST")).thenReturn(Optional.of(new EvcsLiveState("ST",2,List.of("ccs"),1,0,10.0,0.0,Instant.now(),90L)));
    }
    ChargeRequestMessage request(String ev) {
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        requests.save(new ChargeRequest(id,ev,now,50.0,"no_passenger",30.0,"bus_1",0.0));
        return new ChargeRequestMessage(id,ev,now,new Location("bus_1",0.0),50.0,60.0,"ccs","no_passenger",30.0);
    }
    @Test void admissionRechecksCapacityDoesNotTrustStaleCandidateAndDeduplicates() {
        var candidate=new RoutingCandidate(station,new EvcsLiveState("ST",2,List.of("ccs"),1,0,10.0,0.0,Instant.now(),90L),10,1);
        var a=request("A");Assignment booked=admission.reserve(a,candidate,"test").orElseThrow();
        assertThat(booked.getEtaMin()).isEqualTo(20);
        assertThat(booked.getExpiresAt()).isAfter(booked.getReservedAt().plusSeconds(3600));
        assertThat(booked.getPlannedChargeKw()).isEqualTo(10);
        assertThat(admission.reserve(a,candidate,"test").orElseThrow().getAssignmentId()).isEqualTo(booked.getAssignmentId());
        assertThat(admission.reserve(request("B"),candidate,"test")).isEmpty();
        assertThat(assignments.findAll()).hasSize(1);
        verify(state,never()).occupySlot(anyString(),anyLong());
    }
    @Test void persistedExpiredCommitmentIsReconciledWithoutTimers() {
        Assignment expired=new Assignment(UUID.randomUUID(),UUID.randomUUID(),"ST",Instant.now().minusSeconds(4000),Instant.now().minusSeconds(1),AssignmentStatus.RESERVED);
        expired.planned("A",0,0,10,Instant.now().minusSeconds(4000),"test");assignments.save(expired);
        admission.reconcile();assertThat(assignments.findById(expired.getAssignmentId()).orElseThrow().getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignments.findById(expired.getAssignmentId()).orElseThrow().getPendingCommandType()).isEqualTo("RELEASE");
    }
}
