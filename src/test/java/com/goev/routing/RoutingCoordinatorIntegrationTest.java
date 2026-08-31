package com.goev.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goev.evcs.Evcs;
import com.goev.evcs.EvcsRepository;
import com.goev.mqtt.EvcsStatusMessage;
import com.goev.state.AssignmentRepository;
import com.goev.state.StateService;

/**
 * End-to-end vertical slice: a charge request arriving over real MQTT
 * results in a real Assignment row and real response/command messages back
 * over MQTT — the "MQTT request in, assignment out over MQTT" wiring the
 * build plan calls for in phase 4. Uses live Mosquitto/Postgres/Redis via
 * Testcontainers, same Docker-availability skip pattern as phases 2 and 3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RoutingCoordinatorIntegrationTest {

    private static GenericContainer<?> mosquitto;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    @BeforeAll
    static void startContainersIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available - skipping routing coordinator integration test");

        mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withClasspathResourceMapping("mosquitto-test.conf", "/mosquitto/config/mosquitto.conf",
                        org.testcontainers.containers.BindMode.READ_ONLY)
                .withExposedPorts(1883)
                .waitingFor(Wait.forListeningPort());
        mosquitto.start();

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
        if (mosquitto != null) {
            mosquitto.stop();
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("goev.mqtt.broker-url",
                () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres.getUsername());
        registry.add("spring.datasource.password", () -> postgres.getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", () -> redis.getHost());
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private EvcsRepository evcsRepository;

    @Autowired
    private StateService stateService;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void feasibleRequestProducesAssignmentAndMqttResponses() throws Exception {
        evcsRepository.save(new Evcs("EVCS-07", 7, 2, 50.0, "ccs,chademo", 100.0, 0.0));
        stateService.onEvcsStatus(new EvcsStatusMessage(
                "EVCS-07", 7, java.util.List.of("ccs", "chademo"), 2, 0, 50.0, 0.0));

        MqttClient device = new MqttClient(brokerUrl(), "test-ev-routing");
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        device.connect();
        device.subscribe("ev/EV-10/response", (topic, msg) -> responses.add(new String(msg.getPayload(), StandardCharsets.UTF_8)));
        device.subscribe("evcs/EVCS-07/command", (topic, msg) -> commands.add(new String(msg.getPayload(), StandardCharsets.UTF_8)));

        UUID requestId = UUID.randomUUID();
        String requestJson = objectMapper.writeValueAsString(new com.goev.mqtt.ChargeRequestMessage(
                requestId, "EV-10", Instant.now(), new com.goev.mqtt.Location("bus_7", 0.0),
                50.0, 60.0, "compact_l2_ccs", "no_passenger", 10.0));

        // Retry publish: the server's inbound subscription is established asynchronously after
        // context startup and this is QoS 1 but the very first delivery attempt can still race it.
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    device.publish("ev/EV-10/request", requestJson.getBytes(StandardCharsets.UTF_8), 1, false);
                    assertThat(responses).isNotEmpty();
                });

        String responsePayload = responses.poll(5, TimeUnit.SECONDS);
        String commandPayload = commands.poll(5, TimeUnit.SECONDS);
        device.disconnect();
        device.close();

        assertThat(responsePayload).contains("EVCS-07").contains("greedy_best_score");
        assertThat(commandPayload).contains("EVCS-07").contains("RESERVE");
        assertThat(assignmentRepository.findAll())
                .anySatisfy(a -> assertThat(a.getRequestId()).isEqualTo(requestId));
    }

    @Test
    void infeasibleRequestProducesRejectionResponse() throws Exception {
        MqttClient device = new MqttClient(brokerUrl(), "test-ev-rejection");
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        device.connect();
        device.subscribe("ev/EV-11/response", (topic, msg) -> responses.add(new String(msg.getPayload(), StandardCharsets.UTF_8)));

        UUID requestId = UUID.randomUUID();
        // No EVCS has been registered near bus 20, so this is infeasible by construction.
        String requestJson = objectMapper.writeValueAsString(new com.goev.mqtt.ChargeRequestMessage(
                requestId, "EV-11", Instant.now(), new com.goev.mqtt.Location("bus_20", 0.0),
                50.0, 60.0, "compact_l2_ccs", "no_passenger", 1.0));

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    device.publish("ev/EV-11/request", requestJson.getBytes(StandardCharsets.UTF_8), 1, false);
                    assertThat(responses).isNotEmpty();
                });

        String responsePayload = responses.poll(5, TimeUnit.SECONDS);
        device.disconnect();
        device.close();

        assertThat(responsePayload).contains("no_feasible_evcs");
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }
}
