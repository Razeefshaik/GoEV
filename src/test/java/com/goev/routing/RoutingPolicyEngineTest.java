package com.goev.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.goev.ev.EvRepository;
import com.goev.evcs.Evcs;
import com.goev.evcs.EvcsRepository;
import com.goev.graph.NetworkGraphService;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.Location;
import com.goev.state.EvcsLiveState;
import com.goev.state.StateService;

/**
 * Pure unit tests (no Spring context) for the greedy policy engine's
 * feasibility filtering and scoring — the graph and Redis dependencies are
 * mocked so this stays focused on routing logic, not graph correctness
 * (already covered by NetworkGraphServiceTest) or Redis behavior (already
 * covered by StateServiceIntegrationTest).
 */
class RoutingPolicyEngineTest {

    private EvcsRepository evcsRepository;
    private EvRepository evRepository;
    private StateService stateService;
    private NetworkGraphService graphService;
    private RoutingPolicyEngine engine;

    @BeforeEach
    void setUp() {
        evcsRepository = mock(EvcsRepository.class);
        evRepository = mock(EvRepository.class);
        stateService = mock(StateService.class);
        graphService = mock(NetworkGraphService.class);
        when(evRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());

        RoutingProperties properties = new RoutingProperties();
        engine = new RoutingPolicyEngine(evcsRepository, evRepository, stateService, graphService, properties,
                new WeightedGreedyScoringStrategy(properties));
    }

    private ChargeRequestMessage request(int busId, double soc, double maxDetourKm, String evModel) {
        return new ChargeRequestMessage(UUID.randomUUID(), "EV-01", Instant.now(),
                new Location("bus_" + busId, 0.0), soc, 60.0, evModel, "no_passenger", maxDetourKm);
    }

    private Evcs evcs(String id, int busId, String... connectors) {
        return new Evcs(id, busId, 2, 50.0, String.join(",", connectors), 100.0, 0.0);
    }

    private EvcsLiveState live(String evcsId, int busId, int availableSlots, int queueLength, List<String> connectors) {
        return new EvcsLiveState(evcsId, busId, connectors, availableSlots, queueLength, 50.0, 0.0, Instant.now(), 90L);
    }

    @Test
    void routeGreedyPicksTheCloserFeasibleCandidate() {
        Evcs near = evcs("EVCS-NEAR", 2, "ccs");
        Evcs far = evcs("EVCS-FAR", 3, "ccs");
        when(evcsRepository.findAll()).thenReturn(List.of(near, far));
        when(stateService.getEvcsState("EVCS-NEAR"))
                .thenReturn(Optional.of(live("EVCS-NEAR", 2, 2, 0, List.of("ccs"))));
        when(stateService.getEvcsState("EVCS-FAR"))
                .thenReturn(Optional.of(live("EVCS-FAR", 3, 2, 0, List.of("ccs"))));
        when(graphService.distanceKm(1, 2)).thenReturn(2.0);
        when(graphService.distanceKm(1, 3)).thenReturn(8.0);
        when(graphService.headroomMva(anyInt())).thenReturn(Optional.of(1.0));

        ChargeRequestMessage req = request(1, 50.0, 10.0, "compact_l2_ccs");

        Optional<RoutingCandidate> result = engine.routeGreedy(req);

        assertThat(result).isPresent();
        assertThat(result.get().evcs().getEvcsId()).isEqualTo("EVCS-NEAR");
    }

    @Test
    void routeGreedyRejectsWhenNoConnectorMatches() {
        Evcs chademoOnly = evcs("EVCS-01", 2, "chademo");
        when(evcsRepository.findAll()).thenReturn(List.of(chademoOnly));
        when(stateService.getEvcsState("EVCS-01"))
                .thenReturn(Optional.of(live("EVCS-01", 2, 2, 0, List.of("chademo"))));
        when(graphService.distanceKm(1, 2)).thenReturn(2.0);
        when(graphService.headroomMva(anyInt())).thenReturn(Optional.of(1.0));

        ChargeRequestMessage req = request(1, 50.0, 10.0, "compact_l2_ccs");

        assertThat(engine.routeGreedy(req)).isEmpty();
    }

    @Test
    void routeGreedyRejectsWhenBeyondMaxDetour() {
        Evcs farStation = evcs("EVCS-01", 2, "ccs");
        when(evcsRepository.findAll()).thenReturn(List.of(farStation));
        when(stateService.getEvcsState("EVCS-01"))
                .thenReturn(Optional.of(live("EVCS-01", 2, 2, 0, List.of("ccs"))));
        when(graphService.distanceKm(1, 2)).thenReturn(20.0);
        when(graphService.headroomMva(anyInt())).thenReturn(Optional.of(1.0));

        ChargeRequestMessage req = request(1, 50.0, 5.0, "compact_l2_ccs");

        assertThat(engine.routeGreedy(req)).isEmpty();
    }

    @Test
    void routeGreedyRejectsWhenEvcsHasNoLiveState() {
        Evcs offline = evcs("EVCS-01", 2, "ccs");
        when(evcsRepository.findAll()).thenReturn(List.of(offline));
        when(stateService.getEvcsState("EVCS-01")).thenReturn(Optional.empty());

        ChargeRequestMessage req = request(1, 50.0, 10.0, "compact_l2_ccs");

        assertThat(engine.routeGreedy(req)).isEmpty();
    }

    @Test
    void routeGreedyRejectsWhenNoFreeSlotAndQueueTooLong() {
        Evcs busy = evcs("EVCS-01", 2, "ccs");
        when(evcsRepository.findAll()).thenReturn(List.of(busy));
        when(stateService.getEvcsState("EVCS-01"))
                .thenReturn(Optional.of(live("EVCS-01", 2, 0, 10, List.of("ccs"))));

        ChargeRequestMessage req = request(1, 50.0, 10.0, "compact_l2_ccs");

        assertThat(engine.routeGreedy(req)).isEmpty();
    }

    @Test
    void routeGreedyRejectsWhenSocInsufficientToReachStation() {
        Evcs distant = evcs("EVCS-01", 2, "ccs");
        when(evcsRepository.findAll()).thenReturn(List.of(distant));
        when(stateService.getEvcsState("EVCS-01"))
                .thenReturn(Optional.of(live("EVCS-01", 2, 2, 0, List.of("ccs"))));
        when(graphService.distanceKm(1, 2)).thenReturn(50.0);
        when(graphService.headroomMva(anyInt())).thenReturn(Optional.of(1.0));

        // 60 kWh battery, 5% SOC, default 10% reserve floor -> 0 usable energy -> 0 km reachable.
        ChargeRequestMessage req = request(1, 5.0, 100.0, "compact_l2_ccs");

        assertThat(engine.routeGreedy(req)).isEmpty();
    }
}
