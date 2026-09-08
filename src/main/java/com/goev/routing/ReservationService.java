package com.goev.routing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.goev.mqtt.AssignmentResponseMessage;
import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.EvcsCommandMessage;
import com.goev.mqtt.EvcsCommandType;
import com.goev.mqtt.MqttGateway;
import com.goev.state.Assignment;
import com.goev.state.AssignmentRepository;
import com.goev.state.AssignmentStatus;
import com.goev.state.StateService;

/**
 * The persistence + MQTT publishing side effects shared by every
 * {@link AssignmentStrategy} — extracted so the greedy and batch strategies
 * don't duplicate how a reservation gets recorded and announced. Also fans
 * out to any {@link AssignmentListener} beans (e.g. the dashboard
 * broadcaster) with the same per-listener error isolation as
 * {@code MqttInboundRouter}.
 */
@Component
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final AssignmentRepository assignmentRepository;
    private final MqttGateway mqttGateway;
    private final RoutingProperties properties;
    private final List<AssignmentListener> listeners;
    private final StateService stateService;

    public ReservationService(AssignmentRepository assignmentRepository, MqttGateway mqttGateway,
                               RoutingProperties properties, List<AssignmentListener> listeners,
                               StateService stateService) {
        this.assignmentRepository = assignmentRepository;
        this.mqttGateway = mqttGateway;
        this.properties = properties;
        this.listeners = listeners;
        this.stateService = stateService;
    }

    public void reserve(ChargeRequestMessage request, RoutingCandidate candidate, String reasonCode) {
        String evcsId = candidate.evcs().getEvcsId();

        Instant reservedAt = Instant.now();
        Instant expiresAt = reservedAt.plusSeconds(properties.getReservationTtlSeconds());
        UUID assignmentId = UUID.randomUUID();

        assignmentRepository.save(new Assignment(
                assignmentId, request.requestId(), evcsId, reservedAt, expiresAt, AssignmentStatus.RESERVED));

        // The occupation window is the reservation TTL, not a real energy-needed/charge-rate
        // estimate — a deliberate simplification (see README) until session-level charging
        // duration is modeled. Without this, the same slot could be handed to a second
        // request before the first EV ever "left", since greedy only sees the EVCS's
        // self-reported live state, and nothing decremented it here before now.
        stateService.occupySlot(evcsId, properties.getReservationTtlSeconds());

        int etaMin = (int) Math.round(candidate.distanceKm() / properties.getAssumedAvgSpeedKmh() * 60.0);

        mqttGateway.publishResponse(request.evId(), new AssignmentResponseMessage(
                request.requestId(), evcsId, candidate.distanceKm(), etaMin, expiresAt, reasonCode));

        mqttGateway.publishCommand(evcsId, new EvcsCommandMessage(
                evcsId, EvcsCommandType.RESERVE, assignmentId.toString(), request.evId(), expiresAt));

        for (AssignmentListener listener : listeners) {
            try {
                listener.onAssignment(request, candidate, etaMin, reasonCode);
            } catch (Exception e) {
                log.warn("AssignmentListener {} failed to process an assignment", listener.getClass().getSimpleName(), e);
            }
        }
    }

    public void reject(ChargeRequestMessage request) {
        mqttGateway.publishResponse(request.evId(),
                new AssignmentResponseMessage(request.requestId(), null, null, null, null, "no_feasible_evcs"));

        for (AssignmentListener listener : listeners) {
            try {
                listener.onRejection(request);
            } catch (Exception e) {
                log.warn("AssignmentListener {} failed to process a rejection", listener.getClass().getSimpleName(), e);
            }
        }
    }
}
