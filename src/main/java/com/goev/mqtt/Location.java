package com.goev.mqtt;

/** A point on the network graph: a bus reference plus an offset along the feeding line. */
public record Location(String nodeRef, Double offsetKm) {

    /** Parses the {@code "bus_6"}-style nodeRef used throughout the doc's payload examples into a bus id. */
    public int busId() {
        if (nodeRef == null || !nodeRef.startsWith("bus_")) {
            throw new IllegalArgumentException("Unrecognized node reference: " + nodeRef);
        }
        return Integer.parseInt(nodeRef.substring("bus_".length()));
    }
}
