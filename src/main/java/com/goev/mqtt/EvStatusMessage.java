package com.goev.mqtt;

import java.time.Instant;

/** Payload of the {@code ev/{evId}/status} heartbeat topic (QoS 0). */
public record EvStatusMessage(
        String evId,
        Instant timestamp,
        Location location,
        Double socPercent,
        String willingness
) {
}
