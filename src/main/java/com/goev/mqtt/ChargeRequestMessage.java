package com.goev.mqtt;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code ev/{evId}/request} topic (QoS 1). */
public record ChargeRequestMessage(
        UUID requestId,
        String evId,
        Instant timestamp,
        Location location,
        Double socPercent,
        Double batteryCapacityKwh,
        String evModel,
        String willingness,
        Double maxDetourKm
) {
}
