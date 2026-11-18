package com.goev.dashboard;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.EvStatusHandler;
import com.goev.mqtt.EvStatusMessage;
import com.goev.mqtt.EvcsStatusHandler;
import com.goev.mqtt.EvcsStatusMessage;
import com.goev.routing.AssignmentListener;
import com.goev.routing.RoutingCandidate;
import com.goev.state.EvcsLiveStateChangedEvent;

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
    private final Map<String,Instant> stationHeartbeat=new ConcurrentHashMap<>();
    private com.goev.state.StateService stateService;
    @org.springframework.beans.factory.annotation.Value("${goev.state.ev-ttl-seconds:90}") private long evTtl=90;
    @org.springframework.beans.factory.annotation.Value("${goev.state.evcs-ttl-seconds:90}") private long stationTtl=90;
    @org.springframework.beans.factory.annotation.Autowired
    public void setStateService(com.goev.state.StateService stateService){this.stateService=stateService;}
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
        stationHeartbeat.put(message.evcsId(),Instant.now());
        latestEvcsStatus.put(message.evcsId(), message);
        messagingTemplate.convertAndSend("/topic/evcs-status", message);
    }

    /**
     * Slot occupation/release ({@link com.goev.state.StateService#occupySlot})
     * isn't a heartbeat, so it can't reach this class via the
     * {@link EvcsStatusHandler} list {@code MqttInboundRouter} dispatches to
     * (that would need {@code StateService} to depend on
     * {@code MqttInboundRouter}, which already depends on {@code StateService}
     * being in that list — a cycle). A plain Spring event sidesteps it.
     */
    @EventListener
    public void onEvcsLiveStateChanged(EvcsLiveStateChangedEvent event) {
        latestEvcsStatus.put(event.message().evcsId(),event.message());
        messagingTemplate.convertAndSend("/topic/evcs-status",event.message());
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
        return latestEvStatus.values().stream().filter(e->e.timestamp()!=null && e.timestamp().isAfter(Instant.now().minusSeconds(evTtl))).toList();
    }

    public Collection<EvcsStatusMessage> evcsSnapshot() {
        return latestEvcsStatus.values().stream().filter(e->stationHeartbeat.getOrDefault(e.evcsId(),Instant.EPOCH).isAfter(Instant.now().minusSeconds(stationTtl)))
            .map(this::stationSnapshot).toList();
    }
    private EvcsStatusMessage stationSnapshot(EvcsStatusMessage e) {
        if(stateService==null)return e;
        try{return stateService.getEvcsState(e.evcsId()).map(s->new EvcsStatusMessage(e.evcsId(),s.getBusId(),s.getConnectorTypes(),
            s.getAvailableSlots(),s.getQueueLength(),s.getChargeRateKw(),s.getCurrentLoadKw(),e.totalSlots(),s.getCommittedReservationIds()))
            .orElseGet(()->new EvcsStatusMessage(e.evcsId(),e.busId(),e.connectorTypes(),0,0,e.chargeRateKw(),null,e.totalSlots(),List.of()));}
        catch(RuntimeException unavailable){return new EvcsStatusMessage(e.evcsId(),e.busId(),e.connectorTypes(),0,0,e.chargeRateKw(),null,e.totalSlots(),List.of());}
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
