package com.goev.graph;

import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import com.goev.evcs.Evcs;
import com.goev.evcs.EvcsRepository;
import com.goev.state.EvcsLiveState;
import com.goev.state.EvcsLiveStateRepository;

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

    private static final double KW_PER_MVA = 1000.0;

    private final BusRepository busRepository;
    private final LineRepository lineRepository;
    private final EvcsRepository evcsRepository;
    private final EvcsLiveStateRepository evcsLiveStateRepository;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph;
    private volatile Map<Integer, Line> lineByReBus;

    public NetworkGraphService(BusRepository busRepository, LineRepository lineRepository,
                                EvcsRepository evcsRepository, EvcsLiveStateRepository evcsLiveStateRepository) {
        this.busRepository = busRepository;
        this.lineRepository = lineRepository;
        this.evcsRepository = evcsRepository;
        this.evcsLiveStateRepository = evcsLiveStateRepository;
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
     * minus Table 1's static peak-load baseline (the network's non-EV
     * consumers) minus the live aggregate load (kW, converted to MVA
     * assuming ~unity power factor — a documented simplification, real
     * conductor data would carry a power factor) of every EVCS currently
     * charging anywhere downstream of this line — i.e. any bus whose only
     * path back to the root passes through it, since that's everywhere the
     * line's current actually flows in a radial feeder. Returns empty for
     * the network's root bus, which has no feeding line and is therefore
     * unconstrained.
     */
    public Optional<Double> headroomMva(int busId) {
        ensureBuilt();
        Line feedingLine = lineByReBus.get(busId);
        if (feedingLine == null) {
            return Optional.empty();
        }
        double staticHeadroom = feedingLine.getMaxLoadingMva() - feedingLine.getPeakLoadMva();
        double liveEvcsLoadMva = liveDownstreamEvcsLoadMva(feedingLine.getSeBus(), feedingLine.getReBus());
        return Optional.of(staticHeadroom - liveEvcsLoadMva);
    }

    private double liveDownstreamEvcsLoadMva(int seBus, int reBus) {
        Set<Integer> downstream = downstreamBuses(seBus, reBus);
        double totalKw = evcsRepository.findAll().stream()
                .filter(evcs -> downstream.contains(evcs.getBusId()))
                .mapToDouble(this::liveLoadKw)
                .sum();
        return totalKw / KW_PER_MVA;
    }

    private double liveLoadKw(Evcs evcs) {
        return evcsLiveStateRepository.findById(evcs.getEvcsId())
                .map(EvcsLiveState::getCurrentLoadKw)
                .filter(Objects::nonNull)
                .orElse(0.0);
    }

    /** Every bus reachable from reBus without crossing back through seBus — the whole subtree that line feeds. */
    private Set<Integer> downstreamBuses(int seBus, int reBus) {
        Set<Integer> visited = new HashSet<>();
        visited.add(seBus);
        visited.add(reBus);
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(reBus);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            for (DefaultWeightedEdge edge : graph.edgesOf(current)) {
                int source = graph.getEdgeSource(edge);
                int target = graph.getEdgeTarget(edge);
                int neighbor = source == current ? target : source;
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        visited.remove(seBus);
        return visited;
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
