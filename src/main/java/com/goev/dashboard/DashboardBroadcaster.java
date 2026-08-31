package com.goev.dashboard;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.EvStatusHandler;
import com.goev.mqtt.EvStatusMessage;
import com.goev.mqtt.EvcsStatusHandler;
import com.goev.mqtt.EvcsStatusMessage;
import com.goev.routing.AssignmentListener;
import com.goev.routing.RoutingCandidate;

/**
 * Bridges the existing handler-list seams (EV/EVCS heartbeats from
 * {@code com.goev.mqtt}, assignment outcomes from
 * {@code com.goev.routing}) to the dashboard's STOMP topics. Self-registers
 * with those seams like every other handler bean — no wiring needed
 * elsewhere. Also keeps a small in-memory snapshot so a freshly opened
 * dashboard has something to show before the next heartbeat arrives.
 */
@Component
public class DashboardBroadcaster implements EvStatusHandler, EvcsStatusHandler, AssignmentListener {

    private static final int MAX_RECENT_ASSIGNMENTS = 20;

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, EvStatusMessage> latestEvStatus = new ConcurrentHashMap<>();
    private final Map<String, EvcsStatusMessage> latestEvcsStatus = new ConcurrentHashMap<>();
    private final Deque<AssignmentNotification> recentAssignments = new ArrayDeque<>(MAX_RECENT_ASSIGNMENTS);

    public DashboardBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void onEvStatus(EvStatusMessage message) {
        latestEvStatus.put(message.evId(), message);
        messagingTemplate.convertAndSend("/topic/ev-status", message);
    }

    @Override
    public void onEvcsStatus(EvcsStatusMessage message) {
        latestEvcsStatus.put(message.evcsId(), message);
        messagingTemplate.convertAndSend("/topic/evcs-status", message);
    }

    @Override
    public void onAssignment(ChargeRequestMessage request, RoutingCandidate candidate, int etaMin, String reasonCode) {
        recordAndBroadcast(new AssignmentNotification(request.requestId(), request.evId(),
                candidate.evcs().getEvcsId(), candidate.distanceKm(), etaMin, reasonCode, Instant.now()));
    }

    @Override
    public void onRejection(ChargeRequestMessage request) {
        recordAndBroadcast(new AssignmentNotification(request.requestId(), request.evId(),
                null, null, null, "no_feasible_evcs", Instant.now()));
    }

    public Collection<EvStatusMessage> evSnapshot() {
        return List.copyOf(latestEvStatus.values());
    }

    public Collection<EvcsStatusMessage> evcsSnapshot() {
        return List.copyOf(latestEvcsStatus.values());
    }

    public synchronized List<AssignmentNotification> recentAssignmentsSnapshot() {
        return List.copyOf(recentAssignments);
    }

    private synchronized void recordAndBroadcast(AssignmentNotification notification) {
        if (recentAssignments.size() >= MAX_RECENT_ASSIGNMENTS) {
            recentAssignments.removeLast();
        }
        recentAssignments.addFirst(notification);
        messagingTemplate.convertAndSend("/topic/assignments", notification);
    }
}
