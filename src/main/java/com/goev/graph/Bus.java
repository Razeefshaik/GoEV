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

    protected Bus() {
        // JPA
    }

    public Bus(Integer busId, String loadType) {
        this.busId = busId;
        this.loadType = loadType;
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
}
