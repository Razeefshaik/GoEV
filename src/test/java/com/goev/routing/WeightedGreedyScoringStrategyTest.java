package com.goev.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.goev.evcs.Evcs;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.Location;
import com.goev.state.EvcsLiveState;

/**
 * Direct tests of the current default {@link ScoringStrategy} — the point of
 * extracting it behind the interface is that it can be tested (and later
 * replaced) in isolation from feasibility filtering, which
 * RoutingPolicyEngineTest already covers.
 */
class WeightedGreedyScoringStrategyTest {

    private final RoutingProperties properties = new RoutingProperties();
    private final WeightedGreedyScoringStrategy strategy = new WeightedGreedyScoringStrategy(properties);

    private ChargeRequestMessage request(double soc, String willingness) {
        return new ChargeRequestMessage(UUID.randomUUID(), "EV-01", Instant.now(),
                new Location("bus_1", 0.0), soc, 60.0, "compact_l2_ccs", willingness, 10.0);
    }

    private Evcs evcs() {
        return new Evcs("EVCS-01", 2, 2, 50.0, "ccs", 100.0, 0.0);
    }

    private EvcsLiveState live(int queueLength) {
        return new EvcsLiveState("EVCS-01", 2, List.of("ccs"), 2, queueLength, 50.0, 0.0, Instant.now(), 90L);
    }

    @Test
    void closerCandidateScoresHigherThanFartherOne() {
        double nearScore = strategy.score(request(50.0, "no_passenger"), evcs(), live(0), 2.0, Optional.of(1.0));
        double farScore = strategy.score(request(50.0, "no_passenger"), evcs(), live(0), 8.0, Optional.of(1.0));

        assertThat(nearScore).isGreaterThan(farScore);
    }

    @Test
    void shorterQueueScoresHigherThanLongerQueueAtSameDistance() {
        double shortQueueScore = strategy.score(request(50.0, "no_passenger"), evcs(), live(0), 3.0, Optional.of(1.0));
        double longQueueScore = strategy.score(request(50.0, "no_passenger"), evcs(), live(5), 3.0, Optional.of(1.0));

        assertThat(shortQueueScore).isGreaterThan(longQueueScore);
    }

    @Test
    void lowerSocIncreasesTheUrgencyContributionAtSameDistance() {
        double lowSocScore = strategy.score(request(10.0, "no_passenger"), evcs(), live(0), 3.0, Optional.of(1.0));
        double highSocScore = strategy.score(request(90.0, "no_passenger"), evcs(), live(0), 3.0, Optional.of(1.0));

        assertThat(lowSocScore).isGreaterThan(highSocScore);
    }
}
