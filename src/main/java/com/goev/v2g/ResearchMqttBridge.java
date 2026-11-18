package com.goev.v2g;

import com.fasterxml.jackson.databind.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;

/** Optional communications demonstration. Durable command outbox retries until application ACK. */
@Component
@ConditionalOnProperty(name="goev.v2g.mqtt-enabled",havingValue="true")
public final class ResearchMqttBridge {
    private static final Logger log=LoggerFactory.getLogger(ResearchMqttBridge.class);
    private final ResearchLedger ledger;private final ObjectMapper mapper;private final String broker;
    private MqttClient client;
    public ResearchMqttBridge(ResearchLedger ledger,ObjectMapper mapper,@Value("${goev.mqtt.broker-url:tcp://localhost:1883}") String broker) {
        this.ledger=ledger;this.mapper=mapper;this.broker=broker;
    }
    @Scheduled(fixedDelay=2000)
    public synchronized void pump() {
        try {
            if(client==null) {
                client=new MqttClient(broker,"goev-v2g-"+UUID.randomUUID(),new MemoryPersistence());
                client.setCallback(new MqttCallback() {
                    public void connectionLost(Throwable e){log.warn("V2G MQTT disconnected: {}",e.getMessage());}
                    public void deliveryComplete(IMqttDeliveryToken token){}
                    public void messageArrived(String topic,MqttMessage message)throws Exception {
                        try {
                            String[] parts=topic.split("/");JsonNode n=mapper.readTree(message.getPayload());
                            if(parts.length==5 && parts[0].equals("v2g") && parts[1].equals("v1") && parts[2].equals("support") && parts[4].equals("request")) {
                                if(!parts[3].equals(n.path("requestId").asText()) || n.path("version").asInt()!=1)
                                    throw new IllegalArgumentException("support topic/payload mismatch");
                                Instant requestTime=Instant.parse(n.path("timestamp").asText());
                                if(Math.abs(Instant.now().getEpochSecond()-requestTime.getEpochSecond())>120)throw new IllegalArgumentException("stale support request");
                                var request=mapper.treeToValue(n.path("request"),ResearchController.Start.class);
                                if(!parts[3].equals(request.requestId()))throw new IllegalArgumentException("request identity mismatch");
                                var scenario=request.scenario()==null?new ScenarioCatalog().load(request.scenarioId()):request.scenario();
                                var run=ledger.start(request.requestId(),scenario,request.policy());
                                client.publish("v2g/v1/support/"+request.requestId()+"/assignment",mapper.writeValueAsBytes(run),1,false);
                                return;
                            }
                            if(parts.length!=5 || !parts[0].equals("v2g") || !parts[1].equals("v1") || !parts[2].equals("station") || !parts[4].equals("ack")
                                || !parts[3].equals(n.path("stationId").asText()) || n.path("version").asInt()!=1)
                                throw new IllegalArgumentException("topic/payload identity/version mismatch");
                            Instant timestamp=Instant.parse(n.path("timestamp").asText());
                            if(Math.abs(Instant.now().getEpochSecond()-timestamp.getEpochSecond())>120)throw new IllegalArgumentException("stale/future ACK timestamp");
                            ledger.acknowledge(n.path("runId").asText(),parts[3],n.path("commandId").asText(),n.path("result").asText());
                        } catch(Exception e){log.warn("Rejected V2G ACK: {}",e.getMessage());}
                    }
                });
            }
            if(!client.isConnected()) {
                MqttConnectOptions options=new MqttConnectOptions();options.setConnectionTimeout(2);options.setCleanSession(true);
                client.connect(options);client.subscribe(new String[]{"v2g/v1/station/+/ack","v2g/v1/support/+/request"},new int[]{1,1});
            }
            // Only first unacknowledged command per station per run. This preserves causal order.
            for(var run:ledger.runs()) {
                Set<String> sent=new HashSet<>();
                for(var command:run.outbox) if(!command.acknowledged && sent.add(command.stationId)) {
                    String payload=mapper.writeValueAsString(Map.of("version",1,"runId",run.id,"timestamp",Instant.now().toString(),"command",command));
                    ledger.attempted(run.id,command.commandId);
                    client.publish("v2g/v1/station/"+command.stationId+"/command",payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
                }
            }
        }catch(Exception e){log.warn("V2G outbox retained for retry: {}",e.getMessage());}
    }
    @PreDestroy public synchronized void close()throws Exception {if(client!=null){if(client.isConnected())client.disconnect();client.close();}}
}
