package com.goev.routing;

import com.goev.mqtt.ChargeRequestMessage;

/**
 * The seam that makes the greedy engine and batch optimizer swappable and
 * directly comparable, per the build plan for phase 5: exactly one
 * implementation is active at a time, selected by {@code goev.routing.mode}
 * ("greedy" or "batch"). {@link RoutingCoordinator} depends only on this
 * interface.
 */
public interface AssignmentStrategy {

    void handleChargeRequest(ChargeRequestMessage request);
}
