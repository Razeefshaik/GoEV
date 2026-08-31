package com.goev.mqtt;

/** Implemented by later phases (state service) to react to {@code ev/{id}/status} heartbeats. */
public interface EvStatusHandler {

    void onEvStatus(EvStatusMessage message);
}
