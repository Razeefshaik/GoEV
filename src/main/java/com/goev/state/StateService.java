package com.goev.state;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.goev.mqtt.ChargeRequestHandler;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.EvStatusHandler;
import com.goev.mqtt.EvStatusMessage;
import com.goev.mqtt.EvcsStatusHandler;
import com.goev.mqtt.EvcsStatusMessage;
import com.goev.mqtt.Location;

/**
 * Registers itself with {@link com.goev.mqtt.MqttInboundRouter} (via the
 * handler interfaces) to keep Redis-backed live state current from
 * heartbeats, and to persist each incoming charge request durably. Deciding
 * what to do with a request (routing) is build phase 4's job, not this
 * class's — this only records state.
 */
@Service
public class StateService implements EvStatusHandler, EvcsStatusHandler, ChargeRequestHandler {

    private final EvLiveStateRepository evLiveStateRepository;
    private final EvcsLiveStateRepository evcsLiveStateRepository;
    private final ChargeRequestRepository chargeRequestRepository;
    private final StateProperties properties;

    public StateService(EvLiveStateRepository evLiveStateRepository,
                         EvcsLiveStateRepository evcsLiveStateRepository,
                         ChargeRequestRepository chargeRequestRepository,
                         StateProperties properties) {
        this.evLiveStateRepository = evLiveStateRepository;
        this.evcsLiveStateRepository = evcsLiveStateRepository;
        this.chargeRequestRepository = chargeRequestRepository;
        this.properties = properties;
    }

    public void updateEvState(EvStatusMessage message) {
        Location location = message.location();
        EvLiveState state = new EvLiveState(
                message.evId(),
                location != null ? location.nodeRef() : null,
                location != null ? location.offsetKm() : null,
                message.socPercent(),
                message.willingness(),
                message.timestamp() != null ? message.timestamp() : Instant.now(),
                properties.getEvTtlSeconds());
        evLiveStateRepository.save(state);
    }

    public void updateEvcsState(EvcsStatusMessage message) {
        EvcsLiveState state = new EvcsLiveState(
                message.evcsId(),
                message.busId(),
                message.connectorTypes(),
                message.availableSlots(),
                message.queueLength(),
                message.chargeRateKw(),
                message.currentLoadKw(),
                Instant.now(),
                properties.getEvcsTtlSeconds());
        evcsLiveStateRepository.save(state);
    }

    public Optional<EvLiveState> getEvState(String evId) {
        return evLiveStateRepository.findById(evId);
    }

    public Optional<EvcsLiveState> getEvcsState(String evcsId) {
        return evcsLiveStateRepository.findById(evcsId);
    }

    private void persistChargeRequest(ChargeRequestMessage message) {
        Location location = message.location();
        ChargeRequest request = new ChargeRequest(
                message.requestId(),
                message.evId(),
                message.timestamp() != null ? message.timestamp() : Instant.now(),
                message.socPercent(),
                message.willingness(),
                message.maxDetourKm(),
                location != null ? location.nodeRef() : null,
                location != null ? location.offsetKm() : null);
        chargeRequestRepository.save(request);
    }

    @Override
    public void onEvStatus(EvStatusMessage message) {
        updateEvState(message);
    }

    @Override
    public void onEvcsStatus(EvcsStatusMessage message) {
        updateEvcsState(message);
    }

    @Override
    public void onChargeRequest(ChargeRequestMessage message) {
        persistChargeRequest(message);
    }
}
