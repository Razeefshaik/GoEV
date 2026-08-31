package com.goev.state;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How long live state survives in Redis without a fresh heartbeat, kept in config so it can be tuned without a rebuild. */
@ConfigurationProperties(prefix = "goev.state")
public class StateProperties {

    private long evTtlSeconds = 90;
    private long evcsTtlSeconds = 90;

    public long getEvTtlSeconds() {
        return evTtlSeconds;
    }

    public void setEvTtlSeconds(long evTtlSeconds) {
        this.evTtlSeconds = evTtlSeconds;
    }

    public long getEvcsTtlSeconds() {
        return evcsTtlSeconds;
    }

    public void setEvcsTtlSeconds(long evcsTtlSeconds) {
        this.evcsTtlSeconds = evcsTtlSeconds;
    }
}
