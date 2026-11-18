package com.goev.routing;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

/**
 * The reason a batch optimizer exists rather than just calling the greedy
 * engine per request: {@link #evcsCapacity} sees every request in the
 * window at once and won't over-commit a station's slots the way assigning
 * one request at a time could. Soft constraints reuse each candidate's
 * {@link ScoringStrategy}-computed score (same formula the greedy engine
 * uses) so the two are directly comparable, plus a per-request preference
 * for being assigned at all over being left out.
 */
public class BatchRoutingConstraintProvider implements ConstraintProvider {

    /** Scales the ~[0,1] per-candidate score into HardSoftScore's integer soft levels. */
    private static final int SCORE_SCALE = 1000;

    /** Larger than any single candidate's scaled score, so the solver always prefers assigning over leaving unassigned when a slot exists. */
    private static final int UNASSIGNED_PENALTY = 2000;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                evcsCapacity(factory),
                preferHigherScoringCandidate(factory),
                penalizeUnassignedRequest(factory)
        };
    }

    private Constraint evcsCapacity(ConstraintFactory factory) {
        return factory.forEach(RequestAssignment.class)
                .filter(ra -> ra.getAssignedCandidate() != null)
                .groupBy(ra -> ra.getAssignedCandidate().evcs().getEvcsId(), ConstraintCollectors.toList())
                .filter((evcsId, assigned) -> assigned.size() > capacityOf(assigned))
                .penalize(HardSoftScore.ONE_HARD, (evcsId, assigned) -> assigned.size() - capacityOf(assigned))
                .asConstraint("EVCS slot capacity");
    }

    private int capacityOf(java.util.List<RequestAssignment> assignedToSameEvcs) {
        Integer slots = assignedToSameEvcs.get(0).getAssignedCandidate().liveState().getAvailableSlots();
        return slots == null ? 0 : slots;
    }

    private Constraint preferHigherScoringCandidate(ConstraintFactory factory) {
        return factory.forEach(RequestAssignment.class)
                .filter(ra -> ra.getAssignedCandidate() != null)
                .reward(HardSoftScore.ONE_SOFT,
                        ra -> (int) Math.round(ra.getAssignedCandidate().score() * SCORE_SCALE))
                .asConstraint("Prefer higher scoring candidate");
    }

    private Constraint penalizeUnassignedRequest(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(RequestAssignment.class)
                .filter(ra -> ra.getAssignedCandidate() == null)
                .penalize(HardSoftScore.ofSoft(UNASSIGNED_PENALTY))
                .asConstraint("Penalize unassigned request");
    }
}
