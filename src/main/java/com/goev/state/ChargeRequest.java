package com.goev.state;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Durable record of an {@code ev/{id}/request} message. Carries
 * {@code locationNodeRef}/{@code locationOffsetKm} beyond the architecture
 * doc's ER diagram for CHARGE_REQUEST — routing (build phase 4) needs the
 * EV's location to filter/score candidates, and the doc's own message
 * payload already includes it, so leaving it off the durable record would
 * lose data the system needs to function.
 */
@Entity
public class ChargeRequest {

    @Id
    private UUID requestId;

    private String evId;
    private Instant requestedAt;
    private Double socPercent;
    private String willingness;
    private Double maxDetourKm;
    private String locationNodeRef;
    private Double locationOffsetKm;

    protected ChargeRequest() {
        // JPA
    }

    public ChargeRequest(UUID requestId, String evId, Instant requestedAt, Double socPercent, String willingness,
                          Double maxDetourKm, String locationNodeRef, Double locationOffsetKm) {
        this.requestId = requestId;
        this.evId = evId;
        this.requestedAt = requestedAt;
        this.socPercent = socPercent;
        this.willingness = willingness;
        this.maxDetourKm = maxDetourKm;
        this.locationNodeRef = locationNodeRef;
        this.locationOffsetKm = locationOffsetKm;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getEvId() {
        return evId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Double getSocPercent() {
        return socPercent;
    }

    public String getWillingness() {
        return willingness;
    }

    public Double getMaxDetourKm() {
        return maxDetourKm;
    }

    public String getLocationNodeRef() {
        return locationNodeRef;
    }

    public Double getLocationOffsetKm() {
        return locationOffsetKm;
    }
}
