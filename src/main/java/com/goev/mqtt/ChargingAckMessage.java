package com.goev.mqtt;
import java.time.Instant;
public record ChargingAckMessage(String evcsId,String reservationId,String commandType,String result,Instant timestamp) {}
