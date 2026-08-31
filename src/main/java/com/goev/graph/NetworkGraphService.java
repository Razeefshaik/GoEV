package com.goev.graph;

import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps the distribution network (Table 1) as a JGraphT weighted graph, with
 * edge weight = estimated line length in km. Distances are shortest-path
 * along the radial network; headroom is read directly off the line feeding
 * a given bus. The graph is built lazily from the repositories and cached,
 * so it always reflects whatever has been seeded/persisted so far and
 * doesn't depend on startup bean ordering against the seed loader.
 */
@Service
public class NetworkGraphService {

    private final BusRepository busRepository;
    private final LineRepository lineRepository;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph;
    private volatile Map<Integer, Line> lineByReBus;

    public NetworkGraphService(BusRepository busRepository, LineRepository lineRepository) {
        this.busRepository = busRepository;
        this.lineRepository = lineRepository;
    }

    /** Forces the next query to rebuild the graph from the repositories. */
    public void invalidate() {
        lock.lock();
        try {
            graph = null;
            lineByReBus = null;
        } finally {
            lock.unlock();
        }
    }

    public double distanceKm(int busIdA, int busIdB) {
        ensureBuilt();
        if (!graph.containsVertex(busIdA) || !graph.containsVertex(busIdB)) {
            throw new IllegalArgumentException("Unknown bus id: " + busIdA + " or " + busIdB);
        }
        DijkstraShortestPath<Integer, DefaultWeightedEdge> dijkstra = new DijkstraShortestPath<>(graph);
        Double distance = dijkstra.getPathWeight(busIdA, busIdB);
        if (distance == null || distance.isInfinite()) {
            throw new IllegalStateException("No path between bus " + busIdA + " and bus " + busIdB);
        }
        return distance;
    }

    /**
     * Remaining capacity (MVA) on the line feeding this bus: max_loading_mva
     * minus its Table 1 peak load. Returns empty for the network's root bus,
     * which has no feeding line and is therefore unconstrained. This is a
     * static baseline until live EVCS load is wired in (build phase 7).
     */
    public Optional<Double> headroomMva(int busId) {
        ensureBuilt();
        Line feedingLine = lineByReBus.get(busId);
        if (feedingLine == null) {
            return Optional.empty();
        }
        return Optional.of(feedingLine.getMaxLoadingMva() - feedingLine.getPeakLoadMva());
    }

    private void ensureBuilt() {
        if (graph != null) {
            return;
        }
        lock.lock();
        try {
            if (graph != null) {
                return;
            }
            build();
        } finally {
            lock.unlock();
        }
    }

    private void build() {
        SimpleWeightedGraph<Integer, DefaultWeightedEdge> newGraph =
                new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        busRepository.findAll().forEach(bus -> newGraph.addVertex(bus.getBusId()));

        List<Line> lines = lineRepository.findAll();
        for (Line line : lines) {
            DefaultWeightedEdge edge = newGraph.addEdge(line.getSeBus(), line.getReBus());
            if (edge != null) {
                newGraph.setEdgeWeight(edge, line.getEstLengthKm());
            }
        }

        this.lineByReBus = lines.stream()
                .collect(java.util.stream.Collectors.toMap(Line::getReBus, l -> l));
        this.graph = newGraph;
    }
}
