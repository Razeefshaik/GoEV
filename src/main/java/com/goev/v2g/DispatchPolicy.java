package com.goev.v2g;

import java.util.*;
import static com.goev.v2g.Model.*;

/** Heuristic: maximize proportional served demand, subject to fully feasible power flow. */
public final class DispatchPolicy {
    private final RadialPowerFlow solver;
    private final Settings settings;
    private final double requestedKw;
    public DispatchPolicy(Scenario s) {
        solver=new RadialPowerFlow(s.branches());settings=s.settings();
        requestedKw=s.branches().stream().mapToDouble(b->b.peakMva()*1000*settings.pf()*settings.demandScale()).sum();
    }
    public Dispatch evaluate(Map<Integer,Double> injections) {
        // A bounded search over injection curtailment makes no monotonicity assumption about generation.
        Dispatch best=null;
        for(int k=10;k>=0;k--) {
            double factor=k/10.0;Map<Integer,Double> scaled=new TreeMap<>();
            injections.forEach((b,p)->scaled.put(b,p*factor));
            Flow full=solver.solve(settings,1,scaled);
            double fraction=1;Flow chosen=full;
            if(!solver.feasible(full,settings)) {
                // Locate a feasible interval first: reverse flow may make zero load infeasible.
                double feasible=-1;
                for(int j=19;j>=0;j--) {
                    double f=j/20.0;Flow flow=solver.solve(settings,f,scaled);
                    if(solver.feasible(flow,settings)){feasible=f;chosen=flow;break;}
                }
                if(feasible<0)continue;
                double low=feasible,high=Math.min(1,feasible+0.05);
                for(int j=0;j<24;j++) {
                    double f=(low+high)/2;Flow flow=solver.solve(settings,f,scaled);
                    if(solver.feasible(flow,settings)){low=f;chosen=flow;}else high=f;
                }
                fraction=low;
            }
            Dispatch candidate=new Dispatch(fraction,factor,requestedKw,requestedKw*fraction,requestedKw*(1-fraction),chosen);
            if(best==null || candidate.servedKw()>best.servedKw()+1e-5 ||
                (Math.abs(candidate.servedKw()-best.servedKw())<1e-5 && generation(scaled)<generation(injections)*best.injectionScale()))best=candidate;
            if(injections.isEmpty())break;
        }
        if(best==null)throw new IllegalStateException("No feasible load-shedding/curtailment state; dispatch refused");
        return best;
    }
    private double generation(Map<Integer,Double> x){return x.values().stream().mapToDouble(Double::doubleValue).sum();}
}
