package com.goev.mqtt;

/** A point on the network graph: a bus reference plus an offset along the feeding line. */
public record Location(String nodeRef, Double offsetKm) {
}
