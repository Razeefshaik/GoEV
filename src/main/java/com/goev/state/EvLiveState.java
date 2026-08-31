package com.goev.state;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

/**
 * Live EV state from the {@code ev/{id}/status} heartbeat, held in Redis
 * only — the durable {@code Ev} JPA entity (com.goev.ev) holds static
 * vehicle characteristics, not this. The Redis key expires {@code ttlSeconds}
 * after the last write, so a stalled/disconnected EV silently drops out of
 * live state instead of routing decisions using stale data forever.
 */
@RedisHash("ev_live_state")
public class EvLiveState {

    @Id
    private String evId;

    private String locationNodeRef;
    private Double locationOffsetKm;
    private Double socPercent;
    private String willingness;
    private Instant lastHeartbeatAt;

    @TimeToLive
    private Long ttlSeconds;

    protected EvLiveState() {
        // Spring Data Redis
    }

    public EvLiveState(String evId, String locationNodeRef, Double locationOffsetKm, Double socPercent,
                        String willingness, Instant lastHeartbeatAt, Long ttlSeconds) {
        this.evId = evId;
        this.locationNodeRef = locationNodeRef;
        this.locationOffsetKm = locationOffsetKm;
        this.socPercent = socPercent;
        this.willingness = willingness;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.ttlSeconds = ttlSeconds;
    }

    public String getEvId() {
        return evId;
    }

    public String getLocationNodeRef() {
        return locationNodeRef;
    }

    public Double getLocationOffsetKm() {
        return locationOffsetKm;
    }

    public Double getSocPercent() {
        return socPercent;
    }

    public String getWillingness() {
        return willingness;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }
}
