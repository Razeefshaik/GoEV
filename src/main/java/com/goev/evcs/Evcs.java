package com.goev.evcs;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * No real transformer/station capacity numbers exist for this network yet,
 * so {@link #ratedCapacityKw} is a free variable to be filled in per station
 * as that data becomes available — {@link #currentLoadKw} is a placeholder
 * live figure that build phase 3 (Redis live state) and phase 7 (load/line
 * headroom coupling) will keep updated; both are nullable until then.
 */
@Entity
public class Evcs {

    @Id
    private String evcsId;

    private Integer busId;
    private Integer totalSlots;
    private Double chargeRateKw;

    /** Comma-separated connector types, e.g. "ccs,chademo" — matches the ER diagram's single string column. */
    private String connectorTypes;

    /** Station's overall power capacity (kW) — the ceiling all slots draw against together, independent of per-slot chargeRateKw. */
    private Double ratedCapacityKw;

    /** Live aggregate load (kW) across all active charging sessions at this station. */
    private Double currentLoadKw;

    protected Evcs() {
        // JPA
    }

    public Evcs(String evcsId, Integer busId, Integer totalSlots, Double chargeRateKw, String connectorTypes,
                Double ratedCapacityKw, Double currentLoadKw) {
        this.evcsId = evcsId;
        this.busId = busId;
        this.totalSlots = totalSlots;
        this.chargeRateKw = chargeRateKw;
        this.connectorTypes = connectorTypes;
        this.ratedCapacityKw = ratedCapacityKw;
        this.currentLoadKw = currentLoadKw;
    }

    public String getEvcsId() {
        return evcsId;
    }

    public Integer getBusId() {
        return busId;
    }

    public Integer getTotalSlots() {
        return totalSlots;
    }
    public void setTotalSlots(Integer totalSlots){this.totalSlots=totalSlots;}

    public Double getChargeRateKw() {
        return chargeRateKw;
    }

    public String getConnectorTypes() {
        return connectorTypes;
    }

    public Double getRatedCapacityKw() {
        return ratedCapacityKw;
    }

    public Double getCurrentLoadKw() {
        return currentLoadKw;
    }

    public List<String> connectorTypeList() {
        if (connectorTypes == null || connectorTypes.isBlank()) {
            return List.of();
        }
        return Arrays.asList(connectorTypes.split(","));
    }
}
