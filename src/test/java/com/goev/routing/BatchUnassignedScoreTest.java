package com.goev.routing;

import ai.timefold.solver.core.api.solver.*;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.api.score.HardSoftScore;
import com.goev.mqtt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class BatchUnassignedScoreTest {
    @Test void nullCandidateActuallyReceivesPenaltyInPinnedDependencyVersion() {
        SolverFactory<BatchRoutingSolution> factory=SolverFactory.create(new SolverConfig()
            .withSolutionClass(BatchRoutingSolution.class).withEntityClasses(RequestAssignment.class)
            .withConstraintProviderClass(BatchRoutingConstraintProvider.class));
        var request=new ChargeRequestMessage(UUID.randomUUID(),"EV",Instant.now(),new Location("bus_1",0.0),50.0,60.0,"ccs","no_passenger",10.0);
        var solution=new BatchRoutingSolution(List.of(new RequestAssignment(request,List.of())));
        SolutionManager<BatchRoutingSolution,HardSoftScore> manager=SolutionManager.create(factory);
        assertThat(manager.update(solution)).isEqualTo(HardSoftScore.ofSoft(-2000));
    }
}
