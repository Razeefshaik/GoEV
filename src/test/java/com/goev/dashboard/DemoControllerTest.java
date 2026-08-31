package com.goev.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.goev.dashboard.DemoController.PlaceEvcsRequest;
import com.goev.dashboard.DemoController.SendChargeRequest;

/**
 * Exercises the manual EVCS-placement/charge-request endpoints against a
 * real Redis (routing needs live EVCS state to decide feasibility, so
 * assignment can't be verified without it) — same Docker-availability skip
 * pattern as the other Testcontainers-backed tests in this repo, so this
 * doesn't silently depend on `docker compose up` already being run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoControllerTest {

    private static GenericContainer<?> redis;
    private static GenericContainer<?> mosquitto;

    @BeforeAll
    static void startContainersIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available - skipping demo controller test");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
        redis.start();

        // ReservationService.reserve() publishes the response/command over real MQTT
        // before notifying AssignmentListener beans, so a broker has to be reachable
        // for the assignment to ever reach DashboardBroadcaster in this test.
        mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withClasspathResourceMapping("mosquitto-test.conf", "/mosquitto/config/mosquitto.conf",
                        org.testcontainers.containers.BindMode.READ_ONLY)
                .withExposedPorts(1883)
                .waitingFor(Wait.forListeningPort());
        mosquitto.start();
    }

    @AfterAll
    static void stopContainers() {
        if (mosquitto != null) {
            mosquitto.stop();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> redis.getHost());
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("goev.mqtt.broker-url",
                () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DashboardBroadcaster broadcaster;

    @Test
    void placingAnEvcsMakesItAppearInTheSnapshot() {
        ResponseEntity<Void> response = restTemplate.postForEntity("/api/demo/evcs",
                new PlaceEvcsRequest("EVCS-MANUAL-01", 5, "ccs,chademo", 2, 0, 50.0), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(broadcaster.evcsSnapshot())
                .anySatisfy(evcs -> assertThat(evcs.evcsId()).isEqualTo("EVCS-MANUAL-01"));
    }

    @Test
    void sendingARequestAgainstAManuallyPlacedEvcsProducesAnAssignment() {
        restTemplate.postForEntity("/api/demo/evcs",
                new PlaceEvcsRequest("EVCS-MANUAL-02", 6, "ccs", 2, 0, 50.0), Void.class);

        ResponseEntity<Void> response = restTemplate.postForEntity("/api/demo/request",
                new SendChargeRequest("EV-MANUAL-01", 6, 0.0, 50.0, 62.0, "custom_ccs", "no_passenger", 10.0),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(broadcaster.evSnapshot())
                .anySatisfy(ev -> assertThat(ev.evId()).isEqualTo("EV-MANUAL-01"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(broadcaster.recentAssignmentsSnapshot())
                        .anySatisfy(a -> {
                            assertThat(a.evId()).isEqualTo("EV-MANUAL-01");
                            assertThat(a.evcsId()).isEqualTo("EVCS-MANUAL-02");
                        }));
    }
}
