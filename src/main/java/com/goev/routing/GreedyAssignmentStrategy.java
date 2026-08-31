package com.goev.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.goev.mqtt.ChargeRequestMessage;

/** Phase 4's original behavior: decide and reserve immediately, per request. Active unless {@code goev.routing.mode=batch}. */
@Component
@ConditionalOnProperty(prefix = "goev.routing", name = "mode", havingValue = "greedy", matchIfMissing = true)
public class GreedyAssignmentStrategy implements AssignmentStrategy {

    private final RoutingPolicyEngine policyEngine;
    private final ReservationService reservationService;

    public GreedyAssignmentStrategy(RoutingPolicyEngine policyEngine, ReservationService reservationService) {
        this.policyEngine = policyEngine;
        this.reservationService = reservationService;
    }

    @Override
    public void handleChargeRequest(ChargeRequestMessage request) {
        policyEngine.routeGreedy(request).ifPresentOrElse(
                candidate -> reservationService.reserve(request, candidate, "greedy_best_score"),
                () -> reservationService.reject(request));
    }
}
