package com.goev.routing;

import java.util.Optional;

import com.goev.evcs.Evcs;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.state.EvcsLiveState;

/**
 * The seam named in the architecture doc's class diagram (section 8):
 * {@code RoutingPolicyEngine --> ScoringStrategy}. {@link RoutingPolicyEngine}
 * only depends on this interface, never on a scoring formula directly, so
 * the current weighted-sum implementation ({@link WeightedGreedyScoringStrategy})
 * can be replaced by a different {@code @Component} later — after research
 * into better weighting — without touching feasibility filtering or the
 * MQTT/persistence wiring in {@link RoutingCoordinator}. A pure function of
 * its inputs: no repository or graph lookups here, so implementations stay
 * trivially unit-testable.
 */
public interface ScoringStrategy {

    /**
     * @param headroomMva remaining capacity on the line feeding the candidate's bus, already
     *                     resolved by the caller ({@code Optional.empty()} for an unconstrained/root bus)
     */
    double score(ChargeRequestMessage request, Evcs evcs, EvcsLiveState liveState, double distanceKm,
                 Optional<Double> headroomMva);
}
