package com.goev.routing;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.goev.evcs.Evcs;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.state.EvcsLiveState;

/**
 * The current scoring formula (weighted sum of distance, SOC urgency, queue
 * length, line headroom, and a willingness term) — my own synthesis of
 * section 7's qualitative description into actual math, kept as-is for now
 * per the user's direction pending later research into better weighting.
 * Swap this {@code @Component} for a different {@link ScoringStrategy}
 * implementation when that research lands; nothing else in the routing
 * package needs to change.
 */
@Component
public class WeightedGreedyScoringStrategy implements ScoringStrategy {

    private final RoutingProperties properties;

    public WeightedGreedyScoringStrategy(RoutingProperties properties) {
        this.properties = properties;
    }

    @Override
    public double score(ChargeRequestMessage request, Evcs evcs, EvcsLiveState liveState, double distanceKm,
                         Optional<Double> headroomMva) {
        double normalizedDistance = 1.0 / (1.0 + distanceKm);

        double urgency = request.socPercent() == null ? 0.0 : (100.0 - request.socPercent()) / 100.0;
        double urgencyTerm = urgency * normalizedDistance;

        Integer queueLength = liveState.getQueueLength();
        double queueScore = 1.0 / (1.0 + (queueLength == null ? 0 : queueLength));

        double resolvedHeadroomMva = headroomMva.orElse(properties.getHeadroomNormalizationMva());
        double headroomScore = Math.min(1.0, Math.max(0.0, resolvedHeadroomMva / properties.getHeadroomNormalizationMva()));

        boolean noPassenger = "no_passenger".equalsIgnoreCase(request.willingness());
        double willingnessTerm = noPassenger ? headroomScore : normalizedDistance;

        return properties.getWeightDistance() * normalizedDistance
                + properties.getWeightSocUrgency() * urgencyTerm
                + properties.getWeightQueue() * queueScore
                + properties.getWeightHeadroom() * headroomScore
                + properties.getWeightWillingness() * willingnessTerm;
    }
}
