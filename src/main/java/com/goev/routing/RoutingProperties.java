package com.goev.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scoring weights and feasibility/config thresholds for the greedy policy
 * engine (section 7 of the architecture doc), kept in application config so
 * they can be tuned without a rebuild.
 */
@ConfigurationProperties(prefix = "goev.routing")
public class RoutingProperties {

    private double weightDistance = 0.35;
    private double weightSocUrgency = 0.15;
    private double weightQueue = 0.2;
    private double weightHeadroom = 0.2;
    private double weightWillingness = 0.1;

    /** MVA value at which a line is considered "fully unconstrained" for scoring purposes. */
    private double headroomNormalizationMva = 1.0;

    /** An EVCS with no free slot is still feasible if its queue is no longer than this. */
    private int maxAcceptableQueueLength = 3;

    /** Reserve SOC floor used when the requesting EV has no durable record with its own threshold. */
    private double defaultMinSocThresholdPercent = 10.0;

    /** Typical EV consumption, used when the requesting EV has no durable record with its own rate. */
    private double defaultEnergyConsumptionKwhPerKm = 0.2;

    /** Assumed average travel speed for ETA estimation. */
    private double assumedAvgSpeedKmh = 30.0;

    /** How long a greedy reservation is held before it's considered expired. */
    private long reservationTtlSeconds = 300;

    /** Which {@link AssignmentStrategy} handles incoming requests: "greedy" (immediate) or "batch" (windowed, phase 5). */
    private String mode = "greedy";

    /** How often the batch strategy closes its window and solves the accumulated requests jointly. */
    private long batchWindowMs = 10000;

    /** Wall-clock budget given to the Timefold solver per batch window. */
    private long batchSolverTimeLimitMs = 3000;

    public double getWeightDistance() {
        return weightDistance;
    }

    public void setWeightDistance(double weightDistance) {
        this.weightDistance = weightDistance;
    }

    public double getWeightSocUrgency() {
        return weightSocUrgency;
    }

    public void setWeightSocUrgency(double weightSocUrgency) {
        this.weightSocUrgency = weightSocUrgency;
    }

    public double getWeightQueue() {
        return weightQueue;
    }

    public void setWeightQueue(double weightQueue) {
        this.weightQueue = weightQueue;
    }

    public double getWeightHeadroom() {
        return weightHeadroom;
    }

    public void setWeightHeadroom(double weightHeadroom) {
        this.weightHeadroom = weightHeadroom;
    }

    public double getWeightWillingness() {
        return weightWillingness;
    }

    public void setWeightWillingness(double weightWillingness) {
        this.weightWillingness = weightWillingness;
    }

    public double getHeadroomNormalizationMva() {
        return headroomNormalizationMva;
    }

    public void setHeadroomNormalizationMva(double headroomNormalizationMva) {
        this.headroomNormalizationMva = headroomNormalizationMva;
    }

    public int getMaxAcceptableQueueLength() {
        return maxAcceptableQueueLength;
    }

    public void setMaxAcceptableQueueLength(int maxAcceptableQueueLength) {
        this.maxAcceptableQueueLength = maxAcceptableQueueLength;
    }

    public double getDefaultMinSocThresholdPercent() {
        return defaultMinSocThresholdPercent;
    }

    public void setDefaultMinSocThresholdPercent(double defaultMinSocThresholdPercent) {
        this.defaultMinSocThresholdPercent = defaultMinSocThresholdPercent;
    }

    public double getDefaultEnergyConsumptionKwhPerKm() {
        return defaultEnergyConsumptionKwhPerKm;
    }

    public void setDefaultEnergyConsumptionKwhPerKm(double defaultEnergyConsumptionKwhPerKm) {
        this.defaultEnergyConsumptionKwhPerKm = defaultEnergyConsumptionKwhPerKm;
    }

    public double getAssumedAvgSpeedKmh() {
        return assumedAvgSpeedKmh;
    }

    public void setAssumedAvgSpeedKmh(double assumedAvgSpeedKmh) {
        this.assumedAvgSpeedKmh = assumedAvgSpeedKmh;
    }

    public long getReservationTtlSeconds() {
        return reservationTtlSeconds;
    }

    public void setReservationTtlSeconds(long reservationTtlSeconds) {
        this.reservationTtlSeconds = reservationTtlSeconds;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public long getBatchWindowMs() {
        return batchWindowMs;
    }

    public void setBatchWindowMs(long batchWindowMs) {
        this.batchWindowMs = batchWindowMs;
    }

    public long getBatchSolverTimeLimitMs() {
        return batchSolverTimeLimitMs;
    }

    public void setBatchSolverTimeLimitMs(long batchSolverTimeLimitMs) {
        this.batchSolverTimeLimitMs = batchSolverTimeLimitMs;
    }
}
