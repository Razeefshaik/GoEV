package com.goev.v2g;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=com.goevresearch.ResearchApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"goev.v2g.mqtt-enabled=false","logging.level.root=WARN"})
class ResearchApiTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("goev.v2g.ledger",()->temp.resolve("ledger.json").toString());}
    @Autowired TestRestTemplate http;
    @Test void servesActualThymeleafPageAndExecutableJavascriptArrays() {
        var page=http.getForEntity("/v2g",String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Neighbourhood supply support","/v2g/dashboard.js","/v2g/dashboard.css");
        assertThat(http.getForEntity("/v2g/dashboard.js",String.class).getBody()).contains("/api/v2g","comparePolicies");
        assertThat(http.getForEntity("/v2g/coordinates.json",String.class).getStatusCode().value()).isEqualTo(200);
    }
    @Test void apiDeduplicatesAndRejectsUnsupportedModeAndOffsets() {
        String requestId=UUID.randomUUID().toString();
        var body=Map.of("requestId",requestId,"scenarioId","no-shortage","policy","coordinated");
        var first=http.postForEntity("/api/v2g/runs",body,Model.Run.class);
        var second=http.postForEntity("/api/v2g/runs",body,Model.Run.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);assertThat(second.getBody().id).isEqualTo(first.getBody().id);
        var bad=http.postForEntity("/api/v2g/runs",Map.of("requestId",requestId,"scenarioId","no-shortage","policy","none"),String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        var unknown=http.postForEntity("/api/v2g/runs",Map.of("requestId","invalid","scenarioId","no-shortage","policy","none","offsetKm",1),String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(400);
        var s=new ScenarioCatalog().load("no-shortage");
        assertThatThrownBy(()->new Model.Scenario(s.id(),s.description(),s.branches(),s.fleet(),s.stations(),s.settings(),s.datasetSha256(),
            s.travelModel(),s.conductor(),s.resistanceOhmKm(),"ISLANDED")).hasMessageContaining("unsupported");
    }
}
