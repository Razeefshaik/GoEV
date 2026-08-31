package com.goev.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.goev.evcs.Evcs;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.Location;
import com.goev.state.EvcsLiveState;

/**
 * Tests the queuing/draining/dispatch logic in isolation from Timefold
 * itself (covered separately by BatchOptimizerTest) by mocking
 * RoutingPolicyEngine, BatchOptimizer, and ReservationService.
 */
class BatchAssignmentStrategyTest {

    private RoutingPolicyEngine policyEngine;
    private BatchOptimizer batchOptimizer;
    private ReservationService reservationService;
    private BatchAssignmentStrategy strategy;

    @BeforeEach
    void setUp() {
        policyEngine = mock(RoutingPolicyEngine.class);
        batchOptimizer = mock(BatchOptimizer.class);
        reservationService = mock(ReservationService.class);
        strategy = new BatchAssignmentStrategy(policyEngine, batchOptimizer, reservationService);
    }

    private ChargeRequestMessage request(String evId) {
        return new ChargeRequestMessage(UUID.randomUUID(), evId, Instant.now(),
                new Location("bus_1", 0.0), 50.0, 60.0, "compact_l2_ccs", "no_passenger", 10.0);
    }

    private RoutingCandidate candidate() {
        Evcs evcs = new Evcs("EVCS-01", 2, 2, 50.0, "ccs", 100.0, 0.0);
        EvcsLiveState live = new EvcsLiveState("EVCS-01", 2, List.of("ccs"), 2, 0, 50.0, 0.0, Instant.now(), 90L);
        return new RoutingCandidate(evcs, live, 2.0, 0.9);
    }

    @Test
    void closingAnEmptyWindowDoesNothing() {
        strategy.closeBatchWindow();

        verifyNoMoreInteractions(batchOptimizer, reservationService);
    }

    @Test
    void queuedRequestsAreSolvedTogetherAndDrainedOnWindowClose() {
        ChargeRequestMessage reqA = request("EV-A");
        ChargeRequestMessage reqB = request("EV-B");
        RoutingCandidate candidate = candidate();

        when(policyEngine.filterFeasible(reqA)).thenReturn(List.of(candidate));
        when(policyEngine.filterFeasible(reqB)).thenReturn(List.of());
        when(batchOptimizer.solve(anyMap())).thenReturn(Map.of(
                reqA, Optional.of(candidate),
                reqB, Optional.empty()));

        strategy.handleChargeRequest(reqA);
        strategy.handleChargeRequest(reqB);
        strategy.closeBatchWindow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<ChargeRequestMessage, List<RoutingCandidate>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(batchOptimizer).solve(captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys(reqA, reqB);

        verify(reservationService).reserve(reqA, candidate, "batch_joint_optimal");
        verify(reservationService).reject(reqB);

        // A second close with nothing new queued should not re-solve or re-notify.
        strategy.closeBatchWindow();
        verify(batchOptimizer, times(1)).solve(any());
        verifyNoMoreInteractions(batchOptimizer);
    }
}
