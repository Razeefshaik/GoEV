package com.goev.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Exercises NetworkGraphService against the real Table 1 seed data (28 buses,
 * 27 lines) loaded straight from the CSV, not a synthetic fixture.
 */
@DataJpaTest
@Import({NetworkGraphService.class, NetworkSeedLoader.class})
class NetworkGraphServiceTest {

    @Autowired
    private NetworkGraphService graphService;

    @Autowired
    private NetworkSeedLoader seedLoader;

    @BeforeEach
    void seedNetwork() throws IOException {
        seedLoader.loadSeed();
    }

    @Test
    void distanceAlongMainFeederAccumulatesLineLengths() {
        // 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9 -> 10
        double distance = graphService.distanceKm(1, 10);
        assertThat(distance).isCloseTo(31.388, offset(0.001));
    }

    @Test
    void distanceFollowsBranchThroughBus8To28() {
        // 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 27 -> 28
        double distance = graphService.distanceKm(1, 28);
        assertThat(distance).isCloseTo(28.945, offset(0.001));
    }

    @Test
    void distanceToSelfIsZero() {
        assertThat(graphService.distanceKm(15, 15)).isZero();
    }

    @Test
    void distanceForUnknownBusIsInfeasible() {
        assertThatThrownBy(() -> graphService.distanceKm(1, 999))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headroomIsMaxLoadingMinusPeakLoadOnFeedingLine() {
        // Line 1 (bus 1 -> bus 2): max 1.5 MVA, peak load 0.050 MVA
        assertThat(graphService.headroomMva(2)).hasValueSatisfying(
                headroom -> assertThat(headroom).isCloseTo(1.45, offset(0.0001)));
    }

    @Test
    void headroomIsEmptyForRootBusWithNoFeedingLine() {
        assertThat(graphService.headroomMva(1)).isEmpty();
    }
}
