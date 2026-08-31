package com.goev.mqtt;

import java.util.List;

/** Payload of the {@code evcs/{evcsId}/status} topic (QoS 0). */
public record EvcsStatusMessage(
        String evcsId,
        Integer busId,
        List<String> connectorTypes,
        Integer availableSlots,
        Integer queueLength,
        Double chargeRateKw,
        Double currentLoadKw
) {
}
