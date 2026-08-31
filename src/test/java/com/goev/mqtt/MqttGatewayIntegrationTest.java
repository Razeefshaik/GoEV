package com.goev.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Round-trips real MQTT messages through a live Mosquitto broker (Testcontainers),
 * matching the project's "real Postgres and Mosquitto in tests, not mocks" convention.
 * Skips gracefully (JUnit assumption) if the Docker daemon isn't reachable, rather
 * than failing the build in environments without Docker running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MqttGatewayIntegrationTest {

    private static GenericContainer<?> mosquitto;

    @BeforeAll
    static void startBrokerIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available - skipping MQTT integration test");
        mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withClasspathResourceMapping("mosquitto-test.conf", "/mosquitto/config/mosquitto.conf", BindMode.READ_ONLY)
                .withExposedPorts(1883)
                .waitingFor(Wait.forListeningPort());
        mosquitto.start();
    }

    @AfterAll
    static void stopBroker() {
        if (mosquitto != null) {
            mosquitto.stop();
        }
    }

    @DynamicPropertySource
    static void mqttProperties(DynamicPropertyRegistry registry) {
        registry.add("goev.mqtt.broker-url",
                () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
    }

    @Autowired
    private MqttGateway gateway;

    @Autowired
    private CapturingEvcsStatusHandler capturingHandler;

    @Test
    void inboundEvcsStatusIsRoutedToRegisteredHandlers() throws Exception {
        MqttClient device = new MqttClient(brokerUrl(), "test-evcs-device");
        device.connect();
        String json = "{\"evcsId\":\"EVCS-07\",\"busId\":7,\"connectorTypes\":[\"ccs\",\"chademo\"],"
                + "\"availableSlots\":2,\"queueLength\":3,\"chargeRateKw\":50.0,\"currentLoadKw\":140.0}";
        device.publish("evcs/EVCS-07/status", json.getBytes(StandardCharsets.UTF_8), 0, false);
        device.disconnect();
        device.close();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(capturingHandler.received()).hasSize(1));
        assertThat(capturingHandler.received().get(0).evcsId()).isEqualTo("EVCS-07");
    }

    @Test
    void publishResponseSendsMessageOnEvResponseTopic() throws Exception {
        MqttClient device = new MqttClient(brokerUrl(), "test-ev-device");
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        device.connect();
        device.subscribe("ev/EV-01/response",
                (topic, msg) -> received.add(new String(msg.getPayload(), StandardCharsets.UTF_8)));

        gateway.publishResponse("EV-01", new AssignmentResponseMessage(
                UUID.randomUUID(), "EVCS-07", 3.4, 9, Instant.now().plusSeconds(300), "nearest_feasible"));

        String payload = received.poll(5, TimeUnit.SECONDS);
        device.disconnect();
        device.close();

        assertThat(payload).isNotNull().contains("EVCS-07");
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    @TestConfiguration
    static class TestHandlersConfig {

        @Bean
        CapturingEvcsStatusHandler capturingEvcsStatusHandler() {
            return new CapturingEvcsStatusHandler();
        }
    }

    static class CapturingEvcsStatusHandler implements EvcsStatusHandler {
        private final List<EvcsStatusMessage> received = new CopyOnWriteArrayList<>();

        @Override
        public void onEvcsStatus(EvcsStatusMessage message) {
            received.add(message);
        }

        List<EvcsStatusMessage> received() {
            return received;
        }
    }
}
