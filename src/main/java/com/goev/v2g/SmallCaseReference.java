package com.goev.v2g;

import java.util.*;
import static com.goev.v2g.Model.*;

/** Exhaustive 2-EV/2-station static arrival snapshot, with fixed 20 kW candidate powers. */
public final class SmallCaseReference {
    public static Map<String,Object> evaluate() {
        Settings cfg=new Settings(11,1,.95,1.05,500,0,.9,30,1,.1,.1,1e-9,200,1);
        Scenario scenario=new Scenario("small-exact","static snapshot after both EVs arrive",
            List.of(new Branch(1,1,2,.2,.1,1,.3,.001),new Branch(2,2,3,.3,.1,1,.3,.001)),
            List.of(new Ev("E1",2,60,70,20,.2,20,true,true,"CCS",4,10,0,0),new Ev("E2",3,60,70,20,.2,20,true,true,"CCS",4,10,0,0)),
            List.of(new Station("S1",2,true,Set.of("CCS"),1,0,20,20,0,true),new Station("S2",3,true,Set.of("CCS"),1,0,20,20,0,true)),
            cfg,"synthetic-small-v1","synthetic tree travel","ACSR Weasel assumption",.9291,"GRID_CONNECTED");
        DispatchPolicy dispatch=new DispatchPolicy(scenario);double best=-1;int count=0;
        for(int a=-1;a<2;a++)for(int b=-1;b<2;b++) {
            if(a>=0 && a==b)continue;
            Map<Integer,Double> injection=new TreeMap<>();if(a>=0)injection.merge(scenario.stations().get(a).bus(),20.0,Double::sum);
            if(b>=0)injection.merge(scenario.stations().get(b).bus(),20.0,Double::sum);
            best=Math.max(best,dispatch.evaluate(injection).servedKw());count++;
        }
        Run run=new ResearchEngine().start(scenario,"coordinated","small-reference");Map<Integer,Double> injection=new TreeMap<>();
        run.sessions.forEach(s->injection.merge(s.bus,s.assignedKw,Double::sum));double heuristic=dispatch.evaluate(injection).servedKw();
        return Map.of("scope","Static arrived snapshot; two EVs, two single-port stations, fixed 20 kW values; no certificate for full temporal problem",
            "scenario",scenario,"feasibleAssignmentsEnumerated",count,"enumeratedBestServedKw",best,"heuristicServedKw",heuristic,
            "gapKw",best-heuristic,"gapPercent",100*(best-heuristic)/best);
    }
}
