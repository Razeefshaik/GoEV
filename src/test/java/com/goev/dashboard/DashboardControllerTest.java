package com.goev.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.goev.mqtt.EvcsStatusMessage;

/**
 * Doesn't need Docker: serving the dashboard page and its JSON snapshot
 * doesn't require an actual broker/Redis/Postgres connection, just the app
 * context to come up against the H2 test datasource (MQTT/Redis connect
 * asynchronously and don't block startup).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DashboardBroadcaster broadcaster;

    @Test
    void dashboardPageServesHtml() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("GoEV | Neighbourhood supply support");
    }

    @Test
    void guidePageAndPdfAreServed() {
        ResponseEntity<String> page = restTemplate.getForEntity("/guide", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("How to use the GoEV dashboard");

        ResponseEntity<byte[]> pdf = restTemplate.getForEntity("/guide/GoEV-Dashboard-Quick-Guide.pdf", byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getBody()).isNotEmpty();
    }

    @Test
    void snapshotReflectsBroadcastedEvcsStatus() {
        broadcaster.onEvcsStatus(new EvcsStatusMessage(
                "EVCS-DASH-01", 3, List.of("ccs"), 2, 0, 50.0, 0.0));

        ResponseEntity<DashboardSnapshot> response =
                restTemplate.getForEntity("/api/dashboard/snapshot", DashboardSnapshot.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().evcsStations())
                .anySatisfy(evcs -> assertThat(evcs.evcsId()).isEqualTo("EVCS-DASH-01"));
    }

    @Test
    void snapshotReflectsRejectedAssignment() {
        var request = new com.goev.mqtt.ChargeRequestMessage(
                java.util.UUID.randomUUID(), "EV-DASH-01", Instant.now(),
                new com.goev.mqtt.Location("bus_1", 0.0), 50.0, 60.0, "compact_l2_ccs", "no_passenger", 5.0);

        broadcaster.onRejection(request);

        ResponseEntity<DashboardSnapshot> response =
                restTemplate.getForEntity("/api/dashboard/snapshot", DashboardSnapshot.class);

        assertThat(response.getBody().recentAssignments())
                .anySatisfy(a -> {
                    assertThat(a.evId()).isEqualTo("EV-DASH-01");
                    assertThat(a.reasonCode()).isEqualTo("no_feasible_evcs");
                });
    }
}
