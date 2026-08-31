package com.goev.routing;

import com.goev.evcs.Evcs;
import com.goev.state.EvcsLiveState;

/** A feasible EVCS candidate for a charge request, with its computed distance and greedy score. */
public record RoutingCandidate(Evcs evcs, EvcsLiveState liveState, double distanceKm, double score) {
}
