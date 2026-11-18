package com.goev.v2g;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.goev.v2g.Model.*;

@Component
public final class ScenarioCatalog {
    public static final List<String> IDS=List.of("no-shortage","moderate-shortage","weak-voltage","multiple-stations",
        "energy-depletion","unwilling","incompatible","site-bottleneck","travel-reserve","departure",
        "stale-telemetry","excess-injection","insufficient-support");
    public Scenario load(String id) {
        if(!IDS.contains(id))throw new IllegalArgumentException("unknown scenario: "+id);
        try {
            byte[] data;
            try(InputStream input=getClass().getResourceAsStream("/seed/table1_lines_weasel_v1.csv")) {
                if(input==null)throw new IOException("missing Weasel dataset"); data=input.readAllBytes();
            }
            List<Branch> lines=new ArrayList<>();
            for(String row:new String(data,StandardCharsets.UTF_8).split("\\R")) {
                if(row.isBlank() || row.startsWith("line_id"))continue;
                String[] c=row.split(",");
                lines.add(new Branch(Integer.parseInt(c[0]),Integer.parseInt(c[1]),Integer.parseInt(c[2]),
                    Double.parseDouble(c[3]),Double.parseDouble(c[4]),Double.parseDouble(c[5]),Double.parseDouble(c[6]),Double.parseDouble(c[7])));
            }
            double cap=id.equals("no-shortage")?1500:id.equals("weak-voltage")?870:550;
            double voltage=id.equals("weak-voltage")?8.0:11.0;
            double scale=id.equals("insufficient-support")?1.7:id.equals("no-shortage")?0.5:1.0;
            double battery=id.equals("energy-depletion")?12:60;
            double soc=id.equals("travel-reserve")?20.2:70;
            double departure=id.equals("departure")?0.4:4;
            double freshness=id.equals("stale-telemetry")?-1:0;
            boolean willing=!id.equals("unwilling"),compatible=!id.equals("incompatible");
            List<Ev> fleet=new ArrayList<>();
            for(int i=0;i<4;i++) fleet.add(new Ev("EV-"+(i+1),new int[]{10,15,21,28}[i],battery,soc,20,0.2,
                id.equals("excess-injection")?500:20,willing,true,"CCS",departure,20,1,0));
            List<Station> stations=new ArrayList<>();
            for(int i=0;i<3;i++) stations.add(new Station("V2G-"+(i+1),new int[]{14,20,26}[i],compatible,
                Set.of(compatible?"CCS":"CHADEMO"),2,0,id.equals("excess-injection")?500:20,
                id.equals("site-bottleneck")?8:40,freshness,true));
            Settings settings=new Settings(voltage,0.95,0.95,1.05,cap,0,0.9,30,3,1.0/12,0.1,1e-9,200,scale);
            return new Scenario(id,"Deterministic grid-connected, proportional load shedding; "+id,lines,fleet,stations,settings,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
                "Feeder surrogate; generated map layout; no surveyed road routes", "ACSR Weasel (user-selected research assumption, 20 C)",0.9291,"GRID_CONNECTED");
        } catch(Exception e) {throw new IllegalStateException("cannot load scenario",e);}
    }
    public void validate(Scenario scenario) {
        Objects.requireNonNull(scenario);Objects.requireNonNull(scenario.settings());new RadialPowerFlow(scenario.branches());
        Set<Integer> buses=new HashSet<>(Set.of(1));scenario.branches().forEach(b->{buses.add(b.from());buses.add(b.to());});
        Set<String> ids=new HashSet<>();
        for(Ev e:scenario.fleet()) {
            if(e.id()==null || e.id().isBlank() || !ids.add(e.id()) || !buses.contains(e.bus()) || e.connector()==null)
                throw new IllegalArgumentException("invalid/duplicate EV identity or bus");
            positive(e.usableKwh(),"usableKwh");nonnegative(e.soc(),"soc");nonnegative(e.reserveSoc(),"reserveSoc");
            if(e.soc()>100 || e.reserveSoc()>100)throw new IllegalArgumentException("SOC outside [0,100]");
            nonnegative(e.consumptionKwhKm(),"consumption");nonnegative(e.onwardKwh(),"onward reserve");
            positive(e.maxGridKw(),"EV maxGridKw");positive(e.departureHour(),"departureHour");
            nonnegative(e.maxTravelKm(),"maxTravelKm");if(!Double.isFinite(e.telemetryHour()))throw new IllegalArgumentException("EV timestamp");
        }
        ids.clear();
        for(Station s:scenario.stations()) {
            if(s.id()==null || s.id().isBlank() || !ids.add(s.id()) || !buses.contains(s.bus()) || s.connectors()==null || s.connectors().isEmpty()
                || s.totalPorts()<1 || s.externalOccupiedPorts()<0 || s.externalOccupiedPorts()>s.totalPorts())
                throw new IllegalArgumentException("invalid station identity/capacity");
            positive(s.portKw(),"portKw");positive(s.siteKw(),"siteKw");
            if(!Double.isFinite(s.telemetryHour()))throw new IllegalArgumentException("station timestamp");
        }
    }
}
