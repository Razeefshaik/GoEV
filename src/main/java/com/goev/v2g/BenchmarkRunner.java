package com.goev.v2g;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.goev.v2g.Model.*;

/** Runs identical inputs and horizon for every policy; raw per-bus/per-line results are retained. */
public final class BenchmarkRunner {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args.length==0?"output/v2g":args[0]);Files.createDirectories(output);
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();ScenarioCatalog catalog=new ScenarioCatalog();ResearchEngine engine=new ResearchEngine();
        List<Map<String,Object>> rows=new ArrayList<>();
        StringBuilder csv=new StringBuilder("scenario,policy,requested_kwh,served_kwh,unserved_kwh,loss_kwh,ev_grid_kwh,ev_battery_kwh,travel_kwh,min_voltage_pu,max_loading_percent,balance_residual_kw,runtime_ms,accepted,pair_rejections\n");
        for(String id:ScenarioCatalog.IDS) {
            Scenario scenario=catalog.load(id);
            for(String policy:List.of("none","nearest","coordinated")) {
                long start=System.nanoTime();Run run=engine.start(scenario,policy,"benchmark-"+id+"-"+policy);
                engine.advance(run,scenario.settings().horizonHours(),true);double ms=(System.nanoTime()-start)/1e6;
                Metrics m=engine.metrics(run,true);
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(id+"-"+policy+".json").toFile(),run);
                rows.add(Map.of("scenario",id,"policy",policy,"metrics",m,"runtimeMs",ms));
                csv.append(String.format(Locale.ROOT,"%s,%s,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.12f,%.3f,%d,%d%n",
                    id,policy,m.requestedKwh(),m.servedKwh(),m.unservedKwh(),m.lossKwh(),m.deliveredKwh(),m.batteryKwh(),m.travelKwh(),
                    m.minVoltagePu(),m.maxLoadingPercent(),m.maxBalanceResidualKw(),ms,m.accepted(),m.rejected()));
                System.out.printf(Locale.ROOT,"%s / %s: unserved %.3f kWh, delivered %.3f kWh, %.0f ms%n",id,policy,m.unservedKwh(),m.deliveredKwh(),ms);
            }
        }
        Files.writeString(output.resolve("summary.csv"),csv);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("small-exact-reference.json").toFile(),SmallCaseReference.evaluate());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(),Map.of(
            "timestampUtc",Instant.now().toString(),"javaVersion",System.getProperty("java.version"),"seed",0,
            "solver","balanced BFS; 1e-9 pu; 200 iterations; 0.001 kW balance tolerance","results",rows));
        StringBuilder report=new StringBuilder("<!doctype html><meta charset=utf-8><title>GoEV reproducible comparison</title><style>body{font:16px system-ui;margin:2rem}td,th{padding:.6rem;border-bottom:1px solid #ddd;text-align:right}td:first-child{text-align:left}</style><h1>GoEV / ACSR Weasel comparisons</h1><p>Grid-connected research assumptions; same fleet, source cap, loads and horizon. Losses reflect different served demand.</p><table><tr><th>Scenario / policy</th><th>Unserved kWh</th><th>EV kWh</th><th>Loss kWh</th></tr>");
        for(var row:rows){Metrics m=(Metrics)row.get("metrics");report.append(String.format(Locale.ROOT,"<tr><td>%s / %s</td><td>%.3f</td><td>%.3f</td><td>%.3f</td></tr>",row.get("scenario"),row.get("policy"),m.unservedKwh(),m.deliveredKwh(),m.lossKwh()));}
        Files.writeString(output.resolve("comparison.html"),report.append("</table>").toString());
    }
}
