package com.goev.mqtt;

/** Implemented by later phases (state service) to react to {@code evcs/{id}/status} messages. */
public interface EvcsStatusHandler {

    void onEvcsStatus(EvcsStatusMessage message);
}
