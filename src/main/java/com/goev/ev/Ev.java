package com.goev.ev;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Static vehicle/battery characteristics. Live, fast-changing SOC readings
 * from the {@code ev/{id}/status} heartbeat belong in the Redis-backed live
 * state (build phase 3), not here — {@link #lastKnownSocPercent} is only a
 * durable fallback snapshot, not the source of truth for routing decisions.
 */
@Entity
public class Ev {

    @Id
    private String evId;

    private String evModel;
    private Double batteryCapacityKwh;

    /** State of health: % of original battery capacity still usable (degrades with age/cycles). */
    private Double stateOfHealthPercent;

    /** Most recent SOC (%) seen from a status heartbeat; a durable fallback, not the live value. */
    private Double lastKnownSocPercent;

    /** Safety reserve floor (%) — feasibility filtering should not route the EV below this. */
    private Double minSocThresholdPercent;

    /** Desired SOC (%) at the end of a charging session. */
    private Double targetSocPercent;

    /** Average energy use per km, for range/reachability estimation. */
    private Double energyConsumptionKwhPerKm;

    /** Max power (kW) the vehicle's onboard charger/battery can accept — actual charge rate is min(this, EVCS supply rate). */
    private Double maxChargeRateKw;

    protected Ev() {
        // JPA
    }

    public Ev(String evId, String evModel, Double batteryCapacityKwh, Double stateOfHealthPercent,
               Double lastKnownSocPercent, Double minSocThresholdPercent, Double targetSocPercent,
               Double energyConsumptionKwhPerKm, Double maxChargeRateKw) {
        this.evId = evId;
        this.evModel = evModel;
        this.batteryCapacityKwh = batteryCapacityKwh;
        this.stateOfHealthPercent = stateOfHealthPercent;
        this.lastKnownSocPercent = lastKnownSocPercent;
        this.minSocThresholdPercent = minSocThresholdPercent;
        this.targetSocPercent = targetSocPercent;
        this.energyConsumptionKwhPerKm = energyConsumptionKwhPerKm;
        this.maxChargeRateKw = maxChargeRateKw;
    }

    public String getEvId() {
        return evId;
    }

    public String getEvModel() {
        return evModel;
    }

    public Double getBatteryCapacityKwh() {
        return batteryCapacityKwh;
    }

    public Double getStateOfHealthPercent() {
        return stateOfHealthPercent;
    }

    public Double getLastKnownSocPercent() {
        return lastKnownSocPercent;
    }

    public Double getMinSocThresholdPercent() {
        return minSocThresholdPercent;
    }

    public Double getTargetSocPercent() {
        return targetSocPercent;
    }

    public Double getEnergyConsumptionKwhPerKm() {
        return energyConsumptionKwhPerKm;
    }

    public Double getMaxChargeRateKw() {
        return maxChargeRateKw;
    }
}
