package com.goev.state;

import com.goev.mqtt.EvcsStatusMessage;

/**
 * Published whenever live EVCS state changes for a reason other than an
 * inbound heartbeat (currently: slot occupation/release — see
 * {@link StateService#occupySlot}) — so listeners like the dashboard
 * broadcaster see it without {@code StateService} needing a direct,
 * circular dependency back on {@code MqttInboundRouter}'s handler-list
 * dispatch (which is how real heartbeats reach those same listeners).
 */
public record EvcsLiveStateChangedEvent(EvcsStatusMessage message) {
}
