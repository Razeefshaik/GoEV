package com.goev.state;

import java.time.Instant;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.goev.ev.Ev;
import com.goev.ev.EvRepository;
import com.goev.evcs.Evcs;
import com.goev.evcs.EvcsRepository;
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
 *
 * Also auto-registers a durable {@link Evcs}/{@link Ev} row the first time
 * a station or vehicle is ever heard from — the routing engine can only
 * consider an EVCS/EV that has a durable row (phase 1 left this unseeded
 * pending a decision), and "auto-register on first heartbeat" was one of
 * the options flagged for that decision. Only fields the message actually
 * carries are set; the rest stay null until something enriches them later.
 */
@Service
public class StateService implements EvStatusHandler, EvcsStatusHandler, ChargeRequestHandler {

    private final EvLiveStateRepository evLiveStateRepository;
    private final EvcsLiveStateRepository evcsLiveStateRepository;
    private final ChargeRequestRepository chargeRequestRepository;
    private final EvcsRepository evcsRepository;
    private final EvRepository evRepository;
    private final StateProperties properties;
    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher eventPublisher;

    public StateService(EvLiveStateRepository evLiveStateRepository,
                         EvcsLiveStateRepository evcsLiveStateRepository,
                         ChargeRequestRepository chargeRequestRepository,
                         EvcsRepository evcsRepository,
                         EvRepository evRepository,
                         StateProperties properties,
                         TaskScheduler taskScheduler,
                         ApplicationEventPublisher eventPublisher) {
        this.evLiveStateRepository = evLiveStateRepository;
        this.evcsLiveStateRepository = evcsLiveStateRepository;
        this.chargeRequestRepository = chargeRequestRepository;
        this.evcsRepository = evcsRepository;
        this.evRepository = evRepository;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
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

    /**
     * Marks one slot at this EVCS occupied right away (so a second request in the
     * same instant can't also be routed there — greedy assigns one request at a
     * time and only sees "current" live state, so without this an EVCS's reported
     * capacity never actually reflected who'd already been sent there), then
     * schedules the slot to free up again after {@code durationSeconds} — standing
     * in for "the EV finished charging and left" until a real device reports its
     * own departure. The duration is the caller's call (routing owns
     * {@code reservationTtlSeconds}, not this package), not a real
     * energy-needed/charge-rate estimate — a documented simplification.
     */
    public void occupySlot(String evcsId, long durationSeconds) {
        adjustSlots(evcsId, -1);
        taskScheduler.schedule(() -> adjustSlots(evcsId, 1), Instant.now().plusSeconds(durationSeconds));
    }

    private void adjustSlots(String evcsId, int delta) {
        evcsLiveStateRepository.findById(evcsId).ifPresent(state -> {
            int ceiling = evcsRepository.findById(evcsId)
                    .map(Evcs::getTotalSlots)
                    .filter(total -> total != null)
                    .orElse(Integer.MAX_VALUE);
            int current = state.getAvailableSlots() == null ? 0 : state.getAvailableSlots();
            int updatedSlots = Math.max(0, Math.min(current + delta, ceiling));

            EvcsLiveState updated = new EvcsLiveState(
                    evcsId, state.getBusId(), state.getConnectorTypes(), updatedSlots,
                    state.getQueueLength(), state.getChargeRateKw(), state.getCurrentLoadKw(),
                    Instant.now(), state.getTtlSeconds());
            evcsLiveStateRepository.save(updated);

            eventPublisher.publishEvent(new EvcsLiveStateChangedEvent(new EvcsStatusMessage(
                    evcsId, updated.getBusId(), updated.getConnectorTypes(), updated.getAvailableSlots(),
                    updated.getQueueLength(), updated.getChargeRateKw(), updated.getCurrentLoadKw())));
        });
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

    private void ensureEvcsRegistered(EvcsStatusMessage message) {
        if (evcsRepository.existsById(message.evcsId())) {
            return;
        }
        String connectorTypes = message.connectorTypes() == null ? "" : String.join(",", message.connectorTypes());
        evcsRepository.save(new Evcs(message.evcsId(), message.busId(), message.availableSlots(),
                message.chargeRateKw(), connectorTypes, null, message.currentLoadKw()));
    }

    private void ensureEvRegistered(ChargeRequestMessage message) {
        if (evRepository.existsById(message.evId())) {
            return;
        }
        evRepository.save(new Ev(message.evId(), message.evModel(), message.batteryCapacityKwh(),
                null, null, null, null, null, null));
    }

    @Override
    public void onEvStatus(EvStatusMessage message) {
        updateEvState(message);
    }

    @Override
    public void onEvcsStatus(EvcsStatusMessage message) {
        updateEvcsState(message);
        ensureEvcsRegistered(message);
    }

    @Override
    public void onChargeRequest(ChargeRequestMessage message) {
        persistChargeRequest(message);
        ensureEvRegistered(message);
    }
}
