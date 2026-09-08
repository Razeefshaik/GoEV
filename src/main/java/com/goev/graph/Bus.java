package com.goev.graph;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Bus {

    @Id
    private Integer busId;

    /**
     * Nullable — Table 1 doesn't classify buses; left for future enrichment
     * (e.g. "substation" for the root bus, "load" for the rest).
     */
    private String loadType;

    /**
     * Real-world position, anchored to Amtala, South 24 Parganas (a
     * verified real place — see docs) and laid out along Table 1's actual
     * {@code est_length_km} edge lengths with a bearing-spreading
     * algorithm, not independently surveyed per-bus. Nullable so buses
     * added later without known coordinates don't need them.
     */
    private Double latitude;
    private Double longitude;

    protected Bus() {
        // JPA
    }

    public Bus(Integer busId, String loadType) {
        this(busId, loadType, null, null);
    }

    public Bus(Integer busId, String loadType, Double latitude, Double longitude) {
        this.busId = busId;
        this.loadType = loadType;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Integer getBusId() {
        return busId;
    }

    public String getLoadType() {
        return loadType;
    }

    public void setLoadType(String loadType) {
        this.loadType = loadType;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }
}
