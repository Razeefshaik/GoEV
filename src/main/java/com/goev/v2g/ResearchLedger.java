package com.goev.v2g;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static com.goev.v2g.Model.*;

/** Single local durable research ledger. JVM monitor + OS lock + fsync + atomic replacement. */
@Service
public final class ResearchLedger {
    public static class Ledger {
        public int version=1;
        public Map<String,Run> runs=new LinkedHashMap<>();
        public Map<String,String> requests=new LinkedHashMap<>();
        public Map<String,String> requestBodies=new LinkedHashMap<>();
    }
    private final ObjectMapper mapper;
    private final Path path;
    private final ResearchEngine engine=new ResearchEngine();
    public ResearchLedger(ObjectMapper mapper,@Value("${goev.v2g.ledger:var/v2g/ledger.json}") String path) {
        this.mapper=mapper;this.path=Path.of(path).toAbsolutePath();
    }
    private synchronized <T> T access(boolean write,Function<Ledger,T> action) {
        try {
            Files.createDirectories(path.getParent());
            try(FileChannel channel=FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock ignored=channel.lock()) {
                Ledger ledger=Files.exists(path)?mapper.readValue(path.toFile(),Ledger.class):new Ledger();
                if(ledger.version!=1)throw new IllegalStateException("unknown ledger schema version");
                T value=action.apply(ledger);
                if(write) {
                    Path temp=Files.createTempFile(path.getParent(),"ledger-",".tmp");
                    try {
                        byte[] bytes=mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(ledger);
                        try(FileChannel file=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);
                        }
                        Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    } finally {Files.deleteIfExists(temp);}
                }
                return value;
            }
        } catch(IOException e) {throw new IllegalStateException("durable ledger unavailable; operation refused",e);}
    }
    public Run start(String requestId,Scenario scenario,String policy) {
        identity(requestId);
        return access(true,l->{
            String body;
            try{body=mapper.writeValueAsString(List.of(scenario,policy));}catch(IOException e){throw new IllegalArgumentException(e);}
            if(l.requests.containsKey(requestId)) {
                if(!body.equals(l.requestBodies.get(requestId)))throw new IllegalArgumentException("request ID reused with different inputs");
                return l.runs.get(l.requests.get(requestId));
            }
            if(l.runs.size()>=50)throw new IllegalArgumentException("50 retained runs; use a new configured ledger path");
            Run run=engine.start(scenario,policy,requestId);l.runs.put(run.id,run);l.requests.put(requestId,run.id);l.requestBodies.put(requestId,body);return run;
        });
    }
    public Run get(String id){return access(false,l->required(l,id));}
    public Run advance(String id,String operationId,double hours,boolean heartbeat) {
        return mutate(id,operationId,"advance:"+hours+":"+heartbeat,r->engine.advance(r,hours,heartbeat));
    }
    public Run cancel(String id,String operationId,String sessionId) {
        return mutate(id,operationId,"cancel:"+sessionId,r->engine.cancel(r,sessionId));
    }
    private Run mutate(String id,String operationId,String body,java.util.function.Consumer<Run> action) {
        identity(operationId);
        return access(true,l->{Run r=required(l,id);String previous=r.operations.get(operationId);
            if(previous!=null) {if(!previous.equals(body))throw new IllegalArgumentException("operation ID reused");return r;}
            action.accept(r);r.operations.put(operationId,body);r.lastOperation=operationId;return r;});
    }
    public List<Run> runs(){return access(false,l->new ArrayList<>(l.runs.values()));}
    public void attempted(String runId,String commandId) {
        access(true,l->{Run r=required(l,runId);r.outbox.stream().filter(c->c.commandId.equals(commandId)).findFirst().orElseThrow().attempts++;return null;});
    }
    public void acknowledge(String runId,String stationId,String commandId,String result) {
        if(!Set.of("ACCEPTED","REJECTED").contains(result))throw new IllegalArgumentException("invalid acknowledgement");
        access(true,l->{Run r=required(l,runId);Command c=r.outbox.stream().filter(x->x.commandId.equals(commandId)).findFirst().orElseThrow(()->new IllegalArgumentException("unknown command"));
            if(!c.stationId.equals(stationId))throw new IllegalArgumentException("station identity mismatch");
            if(c.acknowledged && !Objects.equals(c.acknowledgement,result))throw new IllegalArgumentException("conflicting acknowledgement");
            if(!c.acknowledged) {
                c.acknowledged=true;c.acknowledgement=result;
                if(result.equals("REJECTED")) {
                    Session s=r.sessions.stream().filter(x->x.id.equals(c.sessionId)).findFirst().orElseThrow();
                    if(!s.terminal()) engine.fail(r,s.id,"device rejected "+c.type);
                }
            }
            return null;});
    }
    private Run required(Ledger l,String id) {
        Run r=l.runs.get(id);if(r==null)throw new IllegalArgumentException("unknown run: "+id);return r;
    }
    private void identity(String id){if(id==null || !id.matches("[a-zA-Z0-9:_-]{1,128}"))throw new IllegalArgumentException("invalid idempotency ID");}
}
