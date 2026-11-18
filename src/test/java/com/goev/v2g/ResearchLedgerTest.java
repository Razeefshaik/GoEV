package com.goev.v2g;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ResearchLedgerTest {
    @TempDir Path temp;
    private ResearchLedger ledger(){return new ResearchLedger(new ObjectMapper().findAndRegisterModules(),temp.resolve("ledger.json").toString());}
    @Test void concurrentAdmissionIdempotencyAndRestartRecovery()throws Exception {
        var l=ledger();var scenario=new ScenarioCatalog().load("moderate-shortage");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->l.start("same-request",scenario,"coordinated"));var b=pool.submit(()->l.start("same-request",scenario,"coordinated"));
            assertThat(a.get().id).isEqualTo(b.get().id);
        }
        var r=l.start("same-request",scenario,"coordinated");
        l.advance(r.id,"tick-1",.25,true);l.advance(r.id,"tick-1",.25,true);
        assertThat(ledger().get(r.id).hour).isEqualTo(.25);
        assertThatThrownBy(()->l.advance(r.id,"tick-1",.5,true)).hasMessageContaining("reused");
        assertThatThrownBy(()->l.start("same-request",scenario,"none")).hasMessageContaining("reused");
        String session=r.sessions.getFirst().id;l.cancel(r.id,"cancel-1",session);l.cancel(r.id,"cancel-2",session);
        assertThat(ledger().get(r.id).sessions.getFirst().status).isEqualTo(Model.Status.CANCELLED);
        var snapshot=ledger().get(r.id);
        double expected=snapshot.sessions.stream().filter(s->s.status==Model.Status.DISCHARGING).mapToDouble(s->s.actualKw).sum();
        assertThat(snapshot.currentV2g.flow().buses().stream().mapToDouble(Model.BusResult::injectionKw).sum()).isCloseTo(expected,within(1e-8));
        assertThat(ledger().get(r.id).outbox.stream().filter(c->c.sessionId.equals(session)&&c.type.equals("STOP")).count()).isEqualTo(1);
    }
    @Test void commandAcknowledgementRejectsWrongStationAndDeduplicates() {
        var l=ledger();var r=l.start("request",new ScenarioCatalog().load("moderate-shortage"),"coordinated");var c=r.outbox.getFirst();
        assertThatThrownBy(()->l.acknowledge(r.id,"wrong",c.commandId,"ACCEPTED")).hasMessageContaining("identity");
        l.acknowledge(r.id,c.stationId,c.commandId,"ACCEPTED");l.acknowledge(r.id,c.stationId,c.commandId,"ACCEPTED");
        assertThat(ledger().get(r.id).outbox.getFirst().acknowledged).isTrue();
        assertThatThrownBy(()->l.acknowledge(r.id,c.stationId,c.commandId,"REJECTED")).hasMessageContaining("conflicting");
    }
}
