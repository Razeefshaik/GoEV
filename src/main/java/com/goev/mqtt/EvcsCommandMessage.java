package com.goev.mqtt;

import java.time.Instant;

/** Payload of the {@code evcs/{evcsId}/command} topic (QoS 1). */
public record EvcsCommandMessage(
        String evcsId,
        EvcsCommandType command,
        String reservationId,
        String evId,
        Instant expiresAt
) {
}
