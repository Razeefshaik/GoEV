package com.goev.routing;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.score.HardSoftScore;

/** The joint-assignment planning problem for one batch window: every pending request, each carrying its own feasible candidates. */
@PlanningSolution
public class BatchRoutingSolution {

    @PlanningEntityCollectionProperty
    private List<RequestAssignment> assignments;

    @PlanningScore
    private HardSoftScore score;

    protected BatchRoutingSolution() {
        // Timefold
    }

    public BatchRoutingSolution(List<RequestAssignment> assignments) {
        this.assignments = assignments;
    }

    public List<RequestAssignment> getAssignments() {
        return assignments;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
}
