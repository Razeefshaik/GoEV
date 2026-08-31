package com.goev.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.goev.ev.Ev;
import com.goev.ev.EvRepository;
import com.goev.evcs.Evcs;
import com.goev.evcs.EvcsRepository;
import com.goev.graph.NetworkGraphService;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.state.EvcsLiveState;
import com.goev.state.StateService;

/**
 * Feasibility filtering per section 7 of the architecture doc, delegating
 * scoring to a swappable {@link ScoringStrategy}. Pure decision logic — no
 * MQTT publishing or Assignment persistence here, so it's unit-testable
 * without a broker or database (see {@link com.goev.routing.RoutingCoordinator}
 * for the side effects).
 *
 * Feasibility, in order: the EVCS must have live state (an EVCS with no
 * recent heartbeat has expired out of Redis per phase 3's TTL and is treated
 * as offline), a free slot or an acceptably short queue, a matching
 * connector, be within the request's max detour, and be within the EV's
 * estimated reachable range.
 */
@Service
public class RoutingPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingPolicyEngine.class);

    private final EvcsRepository evcsRepository;
    private final EvRepository evRepository;
    private final StateService stateService;
    private final NetworkGraphService graphService;
    private final RoutingProperties properties;
    private final ScoringStrategy scoringStrategy;

    public RoutingPolicyEngine(EvcsRepository evcsRepository, EvRepository evRepository, StateService stateService,
                                NetworkGraphService graphService, RoutingProperties properties,
                                ScoringStrategy scoringStrategy) {
        this.evcsRepository = evcsRepository;
        this.evRepository = evRepository;
        this.stateService = stateService;
        this.graphService = graphService;
        this.properties = properties;
        this.scoringStrategy = scoringStrategy;
    }

    public List<RoutingCandidate> filterFeasible(ChargeRequestMessage request) {
        int requestBusId = request.location().busId();
        String requiredConnector = extractConnectorType(request.evModel());
        Optional<Ev> ev = evRepository.findById(request.evId());
        double reachableKm = computeReachableKm(request, ev);

        return evcsRepository.findAll().stream()
                .map(evcs -> stateService.getEvcsState(evcs.getEvcsId()).map(live -> Map.entry(evcs, live)))
                .flatMap(Optional::stream)
                .filter(entry -> hasSlotOrAcceptableQueue(entry.getValue()))
                .filter(entry -> connectorMatches(entry.getValue().getConnectorTypes(), requiredConnector))
                .map(entry -> toCandidateWithDistance(requestBusId, entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .filter(raw -> raw.distanceKm() <= request.maxDetourKm())
                .filter(raw -> raw.distanceKm() <= reachableKm)
                .map(raw -> new RoutingCandidate(raw.evcs(), raw.liveState(), raw.distanceKm(),
                        scoringStrategy.score(request, raw.evcs(), raw.liveState(), raw.distanceKm(),
                                graphService.headroomMva(raw.evcs().getBusId()))))
                .toList();
    }

    public Optional<RoutingCandidate> routeGreedy(ChargeRequestMessage request) {
        return filterFeasible(request).stream().max(Comparator.comparingDouble(RoutingCandidate::score));
    }

    private Optional<RawCandidate> toCandidateWithDistance(int requestBusId, Evcs evcs, EvcsLiveState liveState) {
        try {
            double distanceKm = graphService.distanceKm(requestBusId, evcs.getBusId());
            return Optional.of(new RawCandidate(evcs, liveState, distanceKm));
        } catch (RuntimeException e) {
            log.warn("Could not compute distance from bus {} to EVCS {} (bus {}): {}",
                    requestBusId, evcs.getEvcsId(), evcs.getBusId(), e.getMessage());
            return Optional.empty();
        }
    }

    private double computeReachableKm(ChargeRequestMessage request, Optional<Ev> ev) {
        Double batteryCapacityKwh = request.batteryCapacityKwh() != null
                ? request.batteryCapacityKwh() : ev.map(Ev::getBatteryCapacityKwh).orElse(null);
        if (batteryCapacityKwh == null || request.socPercent() == null) {
            return Double.MAX_VALUE;
        }
        double minSocThresholdPercent = ev.map(Ev::getMinSocThresholdPercent)
                .orElse(properties.getDefaultMinSocThresholdPercent());
        double energyConsumptionKwhPerKm = ev.map(Ev::getEnergyConsumptionKwhPerKm)
                .orElse(properties.getDefaultEnergyConsumptionKwhPerKm());

        double usableSocPercent = Math.max(0.0, request.socPercent() - minSocThresholdPercent);
        double usableEnergyKwh = batteryCapacityKwh * (usableSocPercent / 100.0);
        return usableEnergyKwh / energyConsumptionKwhPerKm;
    }

    private boolean hasSlotOrAcceptableQueue(EvcsLiveState live) {
        Integer slots = live.getAvailableSlots();
        if (slots != null && slots > 0) {
            return true;
        }
        Integer queue = live.getQueueLength();
        return queue != null && queue <= properties.getMaxAcceptableQueueLength();
    }

    private boolean connectorMatches(List<String> stationConnectors, String requiredConnector) {
        return stationConnectors != null
                && stationConnectors.stream().anyMatch(c -> c.equalsIgnoreCase(requiredConnector));
    }

    /**
     * The doc's payload example uses model ids like {@code "compact_l2_ccs"} implying the
     * trailing underscore-separated token is the connector type — no dedicated connector
     * field exists on the EV side, so this is inferred from that convention.
     */
    private String extractConnectorType(String evModel) {
        if (evModel == null) {
            return "";
        }
        int lastUnderscore = evModel.lastIndexOf('_');
        String token = lastUnderscore >= 0 ? evModel.substring(lastUnderscore + 1) : evModel;
        return token.toLowerCase();
    }

    private record RawCandidate(Evcs evcs, EvcsLiveState liveState, double distanceKm) {
    }
}
