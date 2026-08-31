package com.goev.routing;

import java.util.List;
import java.util.UUID;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import com.goev.mqtt.ChargeRequestMessage;

/**
 * One pending charge request within a batch window, paired with its own
 * feasible candidate list (computed once up front by
 * {@link RoutingPolicyEngine#filterFeasible}, identical to what the greedy
 * strategy uses — batching only changes how the pick across requests is
 * made, not feasibility). {@code allowsUnassigned = true} lets the solver
 * leave a request unassigned when every candidate is claimed by
 * higher-value requests in the same window, which becomes a rejection.
 */
@PlanningEntity
public class RequestAssignment {

    @PlanningId
    private UUID requestId;

    private ChargeRequestMessage request;
    private List<RoutingCandidate> candidates;

    @PlanningVariable(valueRangeProviderRefs = "candidateRange", allowsUnassigned = true)
    private RoutingCandidate assignedCandidate;

    protected RequestAssignment() {
        // Timefold
    }

    public RequestAssignment(ChargeRequestMessage request, List<RoutingCandidate> candidates) {
        this.requestId = request.requestId();
        this.request = request;
        this.candidates = candidates;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public ChargeRequestMessage getRequest() {
        return request;
    }

    @ValueRangeProvider(id = "candidateRange")
    public List<RoutingCandidate> getCandidates() {
        return candidates;
    }

    public RoutingCandidate getAssignedCandidate() {
        return assignedCandidate;
    }

    public void setAssignedCandidate(RoutingCandidate assignedCandidate) {
        this.assignedCandidate = assignedCandidate;
    }
}
