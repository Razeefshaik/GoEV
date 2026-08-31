package com.goev.dashboard;

import java.time.Instant;
import java.util.UUID;

/** What the dashboard shows for one resolved request — assigned or rejected. */
public record AssignmentNotification(
        UUID requestId,
        String evId,
        String evcsId,
        Double distanceKm,
        Integer etaMin,
        String reasonCode,
        Instant resolvedAt
) {
}
