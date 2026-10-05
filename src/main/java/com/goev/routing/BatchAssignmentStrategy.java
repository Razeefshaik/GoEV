package com.goev.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.goev.mqtt.ChargeRequestMessage;

/**
 * Queues incoming requests and resolves them jointly when the batch window
 * closes, instead of one at a time. Active only when
 * {@code goev.routing.mode=batch}.
 */
@Component
@ConditionalOnProperty(prefix = "goev.routing", name = "mode", havingValue = "batch")
public class BatchAssignmentStrategy implements AssignmentStrategy {

    private final RoutingPolicyEngine policyEngine;
    private final BatchOptimizer batchOptimizer;
    private final ReservationService reservationService;
    private final List<ChargeRequestMessage> pending = new CopyOnWriteArrayList<>();

    public BatchAssignmentStrategy(RoutingPolicyEngine policyEngine, BatchOptimizer batchOptimizer,
                                    ReservationService reservationService) {
        this.policyEngine = policyEngine;
        this.batchOptimizer = batchOptimizer;
        this.reservationService = reservationService;
    }

    @Override
    public void handleChargeRequest(ChargeRequestMessage request) {
        pending.add(request);
    }

    @Scheduled(fixedDelayString = "${goev.routing.batch-window-ms:10000}")
    void closeBatchWindow() {
        List<ChargeRequestMessage> batch = drainPending();
        if (batch.isEmpty()) {
            return;
        }

        Map<ChargeRequestMessage, List<RoutingCandidate>> feasibleByRequest = batch.stream()
                .collect(Collectors.toMap(r -> r, policyEngine::filterFeasible));

        Map<ChargeRequestMessage, Optional<RoutingCandidate>> results = batchOptimizer.solve(feasibleByRequest);

        results.forEach((request, candidateOpt) -> candidateOpt.ifPresentOrElse(
                candidate -> reservationService.reserve(request, candidate, "batch_joint_heuristic"),
                () -> reservationService.reject(request)));
    }

    private List<ChargeRequestMessage> drainPending() {
        List<ChargeRequestMessage> batch = new ArrayList<>(pending);
        pending.removeAll(batch);
        return batch;
    }
}
