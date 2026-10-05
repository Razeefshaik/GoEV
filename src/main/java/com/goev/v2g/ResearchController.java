package com.goev.v2g;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.goev.v2g.Model.*;

@RestController
@RequestMapping("/api/v2g")
public final class ResearchController {
    private final ResearchLedger ledger;private final ScenarioCatalog catalog;
    public ResearchController(ResearchLedger ledger,ScenarioCatalog catalog){this.ledger=ledger;this.catalog=catalog;}
    public record Start(String requestId,String scenarioId,String policy,Scenario scenario) {}
    public record Advance(String operationId,double hours,boolean simulatedHeartbeat) {}
    public record Cancel(String operationId,String sessionId) {}
    @GetMapping("/scenarios") public List<Scenario> scenarios(){return ScenarioCatalog.IDS.stream().map(catalog::load).toList();}
    @PostMapping("/runs") public Run start(@RequestBody Start input){return ledger.start(input.requestId(),input.scenario()==null?catalog.load(input.scenarioId()):input.scenario(),input.policy());}
    @GetMapping("/runs/{id}") public Run get(@PathVariable String id){return ledger.get(id);}
    @PostMapping("/runs/{id}/advance") public Run advance(@PathVariable String id,@RequestBody Advance input){return ledger.advance(id,input.operationId(),input.hours(),input.simulatedHeartbeat());}
    @PostMapping("/runs/{id}/cancel") public Run cancel(@PathVariable String id,@RequestBody Cancel input){return ledger.cancel(id,input.operationId(),input.sessionId());}
    @GetMapping("/runs/{id}/metrics") public Map<String,Metrics> metrics(@PathVariable String id){Run run=ledger.get(id);ResearchEngine engine=new ResearchEngine();return Map.of("withoutV2g",engine.metrics(run,false),"withV2g",engine.metrics(run,true));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"invalid input":e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> unavailable(IllegalStateException e){return ResponseEntity.status(503).body(Map.of("error",e.getMessage()));}
}
