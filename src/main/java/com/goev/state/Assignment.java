package com.goev.state;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

@Entity
public class Assignment {

    @Id
    private UUID assignmentId;

    private UUID requestId;
    private String evcsId;
    private Instant reservedAt;
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    private AssignmentStatus status;

    protected Assignment() {
        // JPA
    }

    public Assignment(UUID assignmentId, UUID requestId, String evcsId, Instant reservedAt, Instant expiresAt,
                       AssignmentStatus status) {
        this.assignmentId = assignmentId;
        this.requestId = requestId;
        this.evcsId = evcsId;
        this.reservedAt = reservedAt;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getEvcsId() {
        return evcsId;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public AssignmentStatus getStatus() {
        return status;
    }
}
