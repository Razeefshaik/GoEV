package com.goev.routing;

import com.goev.mqtt.ChargeRequestMessage;

/**
 * Notified by {@link ReservationService} whenever a request is resolved,
 * regardless of which {@link AssignmentStrategy} made the decision. Mirrors
 * the {@code com.goev.mqtt} handler-list pattern: any number of beans can
 * implement this (currently just the dashboard broadcaster) without
 * {@code ReservationService} knowing about them.
 */
public interface AssignmentListener {

    void onAssignment(ChargeRequestMessage request, RoutingCandidate candidate, int etaMin, String reasonCode);

    void onRejection(ChargeRequestMessage request);
}
