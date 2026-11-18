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
    private String evId;
    private double distanceKm;
    private int etaMin;
    private double plannedChargeKw;
    private Instant arrivalAt;
    private String reasonCode;
    private boolean commandPending;
    private String pendingCommandType;
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
    public String getEvId(){return evId;}
    public double getDistanceKm(){return distanceKm;}
    public int getEtaMin(){return etaMin;}
    public double getPlannedChargeKw(){return plannedChargeKw;}
    public Instant getArrivalAt(){return arrivalAt;}
    public String getReasonCode(){return reasonCode;}
    public boolean isCommandPending(){return commandPending;}
    public void sent(){commandPending=false;}
    public void complete(){status=AssignmentStatus.COMPLETED;if(evId!=null){commandPending=true;pendingCommandType="RELEASE";}}
    public void confirm(){status=AssignmentStatus.CONFIRMED;}
    public void planned(String evId,double distanceKm,int etaMin,double kw,Instant arrivalAt,String reasonCode){
        this.evId=evId;this.distanceKm=distanceKm;this.etaMin=etaMin;this.plannedChargeKw=kw;
        this.arrivalAt=arrivalAt;this.reasonCode=reasonCode;this.commandPending=true;this.pendingCommandType="RESERVE";
    }
    public String getPendingCommandType(){return pendingCommandType;}
}
