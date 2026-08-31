package com.goev.dashboard;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.EvStatusMessage;
import com.goev.mqtt.EvcsStatusMessage;
import com.goev.mqtt.Location;
import com.goev.mqtt.MqttInboundRouter;

/**
 * Lets the dashboard place an EVCS or send a custom charge request without
 * a browser having to speak MQTT. Both endpoints build the same message
 * types a real device's MQTT payload would carry and hand them to
 * {@link MqttInboundRouter}'s dispatch methods — so a manually-placed EVCS
 * gets the exact same auto-registration/live-state/broadcast treatment as
 * one that heartbeats in over MQTT, and a manual request goes through the
 * exact same routing decision a real EV's request would. This controller
 * is demo/dev tooling only; it deliberately doesn't publish anything back
 * out over MQTT itself (nothing outside this server needs to see it).
 */
@RestController
public class DemoController {

    private final MqttInboundRouter inboundRouter;

    public DemoController(MqttInboundRouter inboundRouter) {
        this.inboundRouter = inboundRouter;
    }

    public record PlaceEvcsRequest(
            String evcsId,
            int busId,
            String connectorTypes,
            Integer availableSlots,
            Integer queueLength,
            Double chargeRateKw
    ) {
    }

    public record SendChargeRequest(
            String evId,
            int busId,
            Double offsetKm,
            Double socPercent,
            Double batteryCapacityKwh,
            String evModel,
            String willingness,
            Double maxDetourKm
    ) {
    }

    @PostMapping("/api/demo/evcs")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public void placeEvcs(@RequestBody PlaceEvcsRequest request) {
        List<String> connectors = request.connectorTypes() == null || request.connectorTypes().isBlank()
                ? List.of()
                : Arrays.stream(request.connectorTypes().split(",")).map(String::trim).toList();

        inboundRouter.dispatchEvcsStatus(new EvcsStatusMessage(
                request.evcsId(),
                request.busId(),
                connectors,
                request.availableSlots() != null ? request.availableSlots() : 1,
                request.queueLength() != null ? request.queueLength() : 0,
                request.chargeRateKw() != null ? request.chargeRateKw() : 50.0,
                0.0));
    }

    @PostMapping("/api/demo/request")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public void sendChargeRequest(@RequestBody SendChargeRequest request) {
        Location location = new Location("bus_" + request.busId(), request.offsetKm() != null ? request.offsetKm() : 0.0);
        Instant now = Instant.now();

        // A heartbeat first, same as a real EV client, so the dashboard's map
        // has a marker for this EV to draw the assignment/rejection path from.
        inboundRouter.dispatchEvStatus(new EvStatusMessage(
                request.evId(), now, location,
                request.socPercent(), request.willingness()));

        inboundRouter.dispatchChargeRequest(new ChargeRequestMessage(
                UUID.randomUUID(), request.evId(), now, location,
                request.socPercent(), request.batteryCapacityKwh(),
                request.evModel(), request.willingness(), request.maxDetourKm()));
    }
}
