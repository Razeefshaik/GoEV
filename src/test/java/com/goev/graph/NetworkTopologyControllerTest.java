package com.goev.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.goev.graph.NetworkTopologyController.Topology;

/** Doesn't need Docker — serving the seeded topology only needs the H2 test datasource. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NetworkTopologyControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void topologyReflectsTheSeededTable1Network() {
        ResponseEntity<Topology> response = restTemplate.getForEntity("/api/network/topology", Topology.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().buses()).hasSize(28);
        assertThat(response.getBody().lines()).hasSize(27);
    }
}
