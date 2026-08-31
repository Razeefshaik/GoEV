package com.goev.mqtt;

/** Implemented by later phases (policy engine) to react to {@code ev/{id}/request} messages. */
public interface ChargeRequestHandler {

    void onChargeRequest(ChargeRequestMessage message);
}
