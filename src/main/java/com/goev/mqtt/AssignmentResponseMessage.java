package com.goev.mqtt;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code ev/{evId}/response} topic (QoS 1). */
public record AssignmentResponseMessage(
        UUID requestId,
        String assignedEvcs,
        Double routeKm,
        Integer etaMin,
        Instant reservationExpiresAt,
        String reasonCode
) {
}
