package com.goev.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Broker connection and topic settings — kept in application config, not hardcoded, per project convention. */
@ConfigurationProperties(prefix = "goev.mqtt")
public class MqttProperties {

    private String brokerUrl = "tcp://localhost:1883";
    private String clientId = "goev-server";
    private String username = "";
    private String password = "";
    private String evStatusTopic = "ev/+/status";
    private String evRequestTopic = "ev/+/request";
    private String evcsStatusTopic = "evcs/+/status";
    private int qosHeartbeat = 0;
    private int qosStateChange = 1;

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public void setBrokerUrl(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEvStatusTopic() {
        return evStatusTopic;
    }

    public void setEvStatusTopic(String evStatusTopic) {
        this.evStatusTopic = evStatusTopic;
    }

    public String getEvRequestTopic() {
        return evRequestTopic;
    }

    public void setEvRequestTopic(String evRequestTopic) {
        this.evRequestTopic = evRequestTopic;
    }

    public String getEvcsStatusTopic() {
        return evcsStatusTopic;
    }

    public void setEvcsStatusTopic(String evcsStatusTopic) {
        this.evcsStatusTopic = evcsStatusTopic;
    }

    public int getQosHeartbeat() {
        return qosHeartbeat;
    }

    public void setQosHeartbeat(int qosHeartbeat) {
        this.qosHeartbeat = qosHeartbeat;
    }

    public int getQosStateChange() {
        return qosStateChange;
    }

    public void setQosStateChange(int qosStateChange) {
        this.qosStateChange = qosStateChange;
    }
}
