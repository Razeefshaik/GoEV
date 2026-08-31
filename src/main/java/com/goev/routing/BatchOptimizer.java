package com.goev.routing;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;

import com.goev.mqtt.ChargeRequestMessage;

/**
 * Timefold-based joint assignment across a batch of pending requests —
 * unlike the greedy engine, which decides each request in isolation, this
 * sees every request in the window at once and can respect shared EVCS
 * capacity across them (see {@link BatchRoutingConstraintProvider}).
 * Feasibility filtering (which candidates each request is even allowed to
 * consider) is the same {@link RoutingPolicyEngine#filterFeasible} the
 * greedy path uses — batching only changes how the pick across requests is
 * made, not what's feasible for any one of them.
 */
@Service
public class BatchOptimizer {

    private final SolverFactory<BatchRoutingSolution> solverFactory;

    public BatchOptimizer(RoutingProperties properties) {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(BatchRoutingSolution.class)
                .withEntityClasses(RequestAssignment.class)
                .withConstraintProviderClass(BatchRoutingConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofMillis(properties.getBatchSolverTimeLimitMs()))
                .withTerminationUnimprovedSpentLimit(Duration.ofMillis(properties.getBatchSolverTimeLimitMs() / 2));
        this.solverFactory = SolverFactory.create(solverConfig);
    }

    /**
     * @param feasibleCandidatesByRequest each pending request paired with its own feasible candidate
     *                                    list (possibly empty, meaning that request can only be rejected)
     * @return each request paired with its assigned candidate, or empty if the solver left it unassigned
     */
    public Map<ChargeRequestMessage, Optional<RoutingCandidate>> solve(
            Map<ChargeRequestMessage, List<RoutingCandidate>> feasibleCandidatesByRequest) {

        if (feasibleCandidatesByRequest.isEmpty()) {
            return Map.of();
        }

        // Sorted by request id so the solver's entity order — and therefore which
        // otherwise-tied local optimum it lands in — is deterministic regardless of
        // the caller's map implementation (e.g. Map.of()'s iteration order is
        // intentionally randomized per JVM run).
        List<RequestAssignment> assignments = feasibleCandidatesByRequest.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().requestId()))
                .map(entry -> new RequestAssignment(entry.getKey(), entry.getValue()))
                .toList();

        Solver<BatchRoutingSolution> solver = solverFactory.buildSolver();
        BatchRoutingSolution solved = solver.solve(new BatchRoutingSolution(assignments));

        return solved.getAssignments().stream()
                .collect(Collectors.toMap(RequestAssignment::getRequest,
                        ra -> Optional.ofNullable(ra.getAssignedCandidate())));
    }
}
