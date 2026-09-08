package com.goev.graph;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes Table 1's bus/line topology so the dashboard can draw the network instead of duplicating the data client-side. */
@RestController
public class NetworkTopologyController {

    private final BusRepository busRepository;
    private final LineRepository lineRepository;

    public NetworkTopologyController(BusRepository busRepository, LineRepository lineRepository) {
        this.busRepository = busRepository;
        this.lineRepository = lineRepository;
    }

    public record BusView(int busId, Double latitude, Double longitude) {
    }

    public record LineView(int lineId, int seBus, int reBus, double estLengthKm, double maxLoadingMva) {
    }

    public record Topology(List<BusView> buses, List<LineView> lines) {
    }

    @GetMapping("/api/network/topology")
    public Topology topology() {
        List<BusView> buses = busRepository.findAll().stream()
                .map(b -> new BusView(b.getBusId(), b.getLatitude(), b.getLongitude()))
                .toList();
        List<LineView> lines = lineRepository.findAll().stream()
                .map(l -> new LineView(l.getLineId(), l.getSeBus(), l.getReBus(), l.getEstLengthKm(), l.getMaxLoadingMva()))
                .toList();
        return new Topology(buses, lines);
    }
}
