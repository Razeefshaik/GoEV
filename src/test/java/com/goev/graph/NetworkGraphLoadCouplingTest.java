package com.goev.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.goev.evcs.Evcs;
import com.goev.evcs.EvcsRepository;
import com.goev.state.EvcsLiveState;
import com.goev.state.EvcsLiveStateRepository;

/**
 * Build phase 7: feeds active EVCS load back into per-line headroom instead
 * of only Table 1's static baseline. Needs a real Redis (live load lives
 * there, not in the JPA-backed Evcs row), so this is split out from
 * NetworkGraphServiceTest (which stays a lightweight, no-Docker slice) —
 * same Docker-availability skip pattern as the rest of this repo's
 * Testcontainers-backed tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NetworkGraphLoadCouplingTest {

    private static GenericContainer<?> redis;

    @BeforeAll
    static void startRedisIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available - skipping network graph load coupling test");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
        redis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> redis.getHost());
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private NetworkGraphService graphService;

    @Autowired
    private NetworkSeedLoader seedLoader;

    @Autowired
    private EvcsRepository evcsRepository;

    @Autowired
    private EvcsLiveStateRepository evcsLiveStateRepository;

    @BeforeEach
    void seedNetwork() throws IOException {
        seedLoader.loadSeed();
    }

    @Test
    void liveEvcsLoadReducesHeadroomOnItsOwnAndEveryUpstreamFeedingLineButNotOtherBranches() {
        // Line 9 (bus 9 -> 10): max 0.3 MVA, peak 0.020 MVA -> static headroom 0.28 MVA.
        // Line 8 (bus 8 -> 9): max 0.4 MVA, peak 0.020 MVA -> static headroom 0.38 MVA.
        // Bus 10 is downstream of both lines, so a live load there should reduce headroom on both.
        evcsRepository.save(new Evcs("EVCS-LOAD-01", 10, 2, 50.0, "ccs", 100.0, 0.0));
        evcsLiveStateRepository.save(new EvcsLiveState(
                "EVCS-LOAD-01", 10, List.of("ccs"), 2, 0, 50.0, 500.0, Instant.now(), 90L));
        // 500 kW live load == 0.5 MVA (unity-power-factor simplification).

        assertThat(graphService.headroomMva(10)).hasValueSatisfying(headroom ->
                assertThat(headroom).isCloseTo(0.28 - 0.5, offset(0.0001)));

        assertThat(graphService.headroomMva(9)).hasValueSatisfying(headroom ->
                assertThat(headroom).isCloseTo(0.38 - 0.5, offset(0.0001)));

        // Bus 12 (fed via 4 -> 11 -> 12, a completely different branch) must be unaffected.
        // Line 11 (bus 11 -> 12): max 0.4 MVA, peak 0.050 MVA -> static headroom 0.35 MVA.
        assertThat(graphService.headroomMva(12)).hasValueSatisfying(headroom ->
                assertThat(headroom).isCloseTo(0.35, offset(0.0001)));
    }
}
