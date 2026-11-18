package com.goev.state;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

/**
 * Live EVCS state from the {@code evcs/{id}/status} heartbeat, held in Redis
 * only — the durable {@code Evcs} JPA entity (com.goev.evcs) holds static
 * station characteristics, not this.
 */
@RedisHash("evcs_live_state")
public class EvcsLiveState {

    @Id
    private String evcsId;

    private Integer busId;
    private List<String> connectorTypes;
    private Integer availableSlots;
    private Integer queueLength;
    private Double chargeRateKw;
    private Double currentLoadKw;
    private Instant lastHeartbeatAt;
    private List<String> committedReservationIds;

    @TimeToLive
    private Long ttlSeconds;

    protected EvcsLiveState() {
        // Spring Data Redis
    }

    public EvcsLiveState(String evcsId, Integer busId, List<String> connectorTypes, Integer availableSlots,
                          Integer queueLength, Double chargeRateKw, Double currentLoadKw, Instant lastHeartbeatAt,
                          Long ttlSeconds) {
        this.evcsId = evcsId;
        this.busId = busId;
        this.connectorTypes = connectorTypes;
        this.availableSlots = availableSlots;
        this.queueLength = queueLength;
        this.chargeRateKw = chargeRateKw;
        this.currentLoadKw = currentLoadKw;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.ttlSeconds = ttlSeconds;
    }

    public String getEvcsId() {
        return evcsId;
    }

    public Integer getBusId() {
        return busId;
    }

    public List<String> getConnectorTypes() {
        return connectorTypes;
    }

    public Integer getAvailableSlots() {
        return availableSlots;
    }

    public Integer getQueueLength() {
        return queueLength;
    }

    public Double getChargeRateKw() {
        return chargeRateKw;
    }

    public Double getCurrentLoadKw() {
        return currentLoadKw;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }
    public List<String> getCommittedReservationIds(){return committedReservationIds;}
    public void setCommittedReservationIds(List<String> ids){committedReservationIds=ids;}
}
