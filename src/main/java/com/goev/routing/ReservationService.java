package com.goev.routing;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import com.goev.mqtt.*;
import com.goev.state.*;

/** Charging notifications; admission and resource ownership live in the persistent ledger. */
@Component
public class ReservationService implements ChargingAckHandler {
    private static final Logger log=LoggerFactory.getLogger(ReservationService.class);
    private final AssignmentRepository assignments;
    private final MqttGateway mqtt;
    private final List<AssignmentListener> listeners;
    private final ChargingAdmission admission;
    public ReservationService(AssignmentRepository assignments,MqttGateway mqtt,List<AssignmentListener> listeners,ChargingAdmission admission){
        this.assignments=assignments;this.mqtt=mqtt;this.listeners=listeners;this.admission=admission;
    }
    public void reserve(ChargeRequestMessage request,RoutingCandidate candidate,String reasonCode) {
        var result=admission.reserve(request,candidate,reasonCode);
        if(result.isEmpty()){reject(request);return;}
        Assignment assignment=result.get();
        try{publish(assignment);}catch(Exception e){log.warn("Charging command retained for retry: {}",e.getMessage());}
        for(AssignmentListener listener:listeners)try{listener.onAssignment(request,candidate,assignment.getEtaMin(),reasonCode);}
            catch(Exception e){log.warn("Assignment listener failed",e);}
    }
    private void publish(Assignment a) {
        if(a.getEvId()==null || !a.isCommandPending())return;
        if("RESERVE".equals(a.getPendingCommandType()))mqtt.publishResponse(a.getEvId(),new AssignmentResponseMessage(
            a.getRequestId(),a.getEvcsId(),a.getDistanceKm(),a.getEtaMin(),a.getExpiresAt(),a.getReasonCode()));
        mqtt.publishCommand(a.getEvcsId(),new EvcsCommandMessage(a.getEvcsId(),EvcsCommandType.valueOf(a.getPendingCommandType()),
            a.getAssignmentId().toString(),a.getEvId(),a.getExpiresAt()));
    }
    @Scheduled(fixedDelay=5000)
    public void reconcileAndRetry() {
        admission.reconcile();
        for(Assignment a:assignments.findByCommandPendingTrue())try{publish(a);}catch(Exception e){log.warn("Charging outbox retry failed: {}",e.getMessage());}
    }
    @Override @org.springframework.transaction.annotation.Transactional
    public void onAck(ChargingAckMessage message) {
        if(message.timestamp()==null || Math.abs(Instant.now().getEpochSecond()-message.timestamp().getEpochSecond())>120)
            throw new IllegalArgumentException("stale charging acknowledgement");
        var a=assignments.lockById(UUID.fromString(message.reservationId())).orElseThrow();
        if(!a.getEvcsId().equals(message.evcsId()))throw new IllegalArgumentException("ACK identity mismatch");
        if(!java.util.Objects.equals(a.getPendingCommandType(),message.commandType()))return;
        if("ACCEPTED".equals(message.result())){a.sent();assignments.save(a);}
        else if("REJECTED".equals(message.result())){a.complete();assignments.save(a);}
        else throw new IllegalArgumentException("unknown charging ACK result");
    }
    public void reject(ChargeRequestMessage request) {
        mqtt.publishResponse(request.evId(),new AssignmentResponseMessage(request.requestId(),null,null,null,null,"no_feasible_evcs"));
        for(AssignmentListener listener:listeners)try{listener.onRejection(request);}catch(Exception e){log.warn("Rejection listener failed",e);}
    }
}
