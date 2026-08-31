package com.goev.graph;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Line {

    @Id
    private Integer lineId;

    private Integer seBus;
    private Integer reBus;
    private Double resistanceOhm;
    private Double reactanceOhm;
    private Double maxLoadingMva;
    private Double peakLoadMva;
    private Double estLengthKm;

    protected Line() {
        // JPA
    }

    public Line(Integer lineId, Integer seBus, Integer reBus, Double resistanceOhm,
                Double reactanceOhm, Double maxLoadingMva, Double peakLoadMva, Double estLengthKm) {
        this.lineId = lineId;
        this.seBus = seBus;
        this.reBus = reBus;
        this.resistanceOhm = resistanceOhm;
        this.reactanceOhm = reactanceOhm;
        this.maxLoadingMva = maxLoadingMva;
        this.peakLoadMva = peakLoadMva;
        this.estLengthKm = estLengthKm;
    }

    public Integer getLineId() {
        return lineId;
    }

    public Integer getSeBus() {
        return seBus;
    }

    public Integer getReBus() {
        return reBus;
    }

    public Double getResistanceOhm() {
        return resistanceOhm;
    }

    public Double getReactanceOhm() {
        return reactanceOhm;
    }

    public Double getMaxLoadingMva() {
        return maxLoadingMva;
    }

    public Double getPeakLoadMva() {
        return peakLoadMva;
    }

    public Double getEstLengthKm() {
        return estLengthKm;
    }
}
