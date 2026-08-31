package com.goev.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.goev.mqtt.ChargeRequestMessage;
import com.goev.mqtt.EvStatusMessage;
import com.goev.mqtt.EvcsStatusMessage;
import com.goev.mqtt.Location;

/**
 * Exercises StateService against a real Postgres (durable records) and a
 * real Redis (live state + TTL expiry), per the project's "real Postgres
 * and Mosquitto/Redis in tests, not mocks" convention. Skips gracefully via
 * a JUnit assumption if the Docker daemon isn't reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StateServiceIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    @BeforeAll
    static void startContainersIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available - skipping state service integration test");

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("goev-test")
                .withUsername("goev")
                .withPassword("goev");
        postgres.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
        redis.start();
    }

    @AfterAll
    static void stopContainers() {
        if (redis != null) {
            redis.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres.getUsername());
        registry.add("spring.datasource.password", () -> postgres.getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", () -> redis.getHost());
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("goev.state.ev-ttl-seconds", () -> 2);
        registry.add("goev.state.evcs-ttl-seconds", () -> 90);
    }

    @Autowired
    private StateService stateService;

    @Autowired
    private ChargeRequestRepository chargeRequestRepository;

    @Test
    void evHeartbeatIsReadableThenExpiresAfterTtl() {
        stateService.onEvStatus(new EvStatusMessage(
                "EV-01", Instant.now(), new Location("bus_6", 1.2), 18.0, "no_passenger"));

        assertThat(stateService.getEvState("EV-01")).isPresent();

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(stateService.getEvState("EV-01")).isEmpty());
    }

    @Test
    void evcsHeartbeatIsReadableViaStateService() {
        stateService.onEvcsStatus(new EvcsStatusMessage(
                "EVCS-07", 7, List.of("ccs", "chademo"), 2, 3, 50.0, 140.0));

        assertThat(stateService.getEvcsState("EVCS-07"))
                .hasValueSatisfying(state -> assertThat(state.getAvailableSlots()).isEqualTo(2));
    }

    @Test
    void chargeRequestIsPersistedDurably() {
        UUID requestId = UUID.randomUUID();
        stateService.onChargeRequest(new ChargeRequestMessage(
                requestId, "EV-02", Instant.now(), new Location("bus_9", 0.5),
                22.0, 62.0, "compact_l2_ccs", "no_passenger", 5.0));

        assertThat(chargeRequestRepository.findById(requestId))
                .hasValueSatisfying(req -> assertThat(req.getEvId()).isEqualTo("EV-02"));
    }
}
