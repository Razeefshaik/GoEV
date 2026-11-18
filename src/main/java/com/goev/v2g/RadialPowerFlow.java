package com.goev.v2g;

import java.util.*;
import static com.goev.v2g.Model.*;

/** Balanced three-phase backward/forward current sweep in physical SI units. No shunts/taps. */
public final class RadialPowerFlow {
    private record C(double re,double im) {
        C add(C b){return new C(re+b.re,im+b.im);} C sub(C b){return new C(re-b.re,im-b.im);}
        C mul(C b){return new C(re*b.re-im*b.im,re*b.im+im*b.re);}
        C div(C b){double d=b.re*b.re+b.im*b.im;return new C((re*b.re+im*b.im)/d,(im*b.re-re*b.im)/d);}
        C conj(){return new C(re,-im);} double abs(){return Math.hypot(re,im);}
    }
    private final List<Branch> order;
    private final Set<Integer> buses=new TreeSet<>(Set.of(1));
    public RadialPowerFlow(List<Branch> lines) {
        if(lines.isEmpty()) throw new IllegalArgumentException("empty feeder");
        Map<Integer,List<Branch>> children=new TreeMap<>(); Set<Integer> incoming=new HashSet<>(),ids=new HashSet<>();
        for(Branch b:lines) {
            nonnegative(b.rOhm(),"R"); nonnegative(b.xOhm(),"X"); positive(b.limitMva(),"MVA limit");
            nonnegative(b.peakMva(),"bus peak");
            if(b.from()<1 || b.to()<1 || b.from()==b.to() || b.to()==1 || !incoming.add(b.to()) || !ids.add(b.id()))
                throw new IllegalArgumentException("invalid radial orientation/duplicate parent/id");
            buses.add(b.from());buses.add(b.to());children.computeIfAbsent(b.from(),k->new ArrayList<>()).add(b);
        }
        order=new ArrayList<>(); Deque<Integer> queue=new ArrayDeque<>(); queue.add(1);
        Set<Integer> seen=new HashSet<>(Set.of(1));
        while(!queue.isEmpty()) for(Branch b:children.getOrDefault(queue.remove(),List.of())) {
            if(!seen.add(b.to())) throw new IllegalArgumentException("cycle");order.add(b);queue.add(b.to());
        }
        if(order.size()!=lines.size() || seen.size()!=buses.size() || lines.size()!=buses.size()-1)
            throw new IllegalArgumentException("disconnected/cyclic feeder");
    }
    public Flow solve(Settings cfg, double servedFraction, Map<Integer,Double> injectionKw) {
        nonnegative(servedFraction,"servedFraction"); if(servedFraction>1) throw new IllegalArgumentException("fraction > 1");
        for(var e:injectionKw.entrySet()) { if(!buses.contains(e.getKey())) throw new IllegalArgumentException("unknown injection bus"); nonnegative(e.getValue(),"injectionKw"); }
        double base=cfg.voltageKv()*1000/Math.sqrt(3);
        Map<Integer,C> v=new TreeMap<>(),iBranch=new HashMap<>(),sLoad=new TreeMap<>();
        Map<Integer,Double> requested=new TreeMap<>(); requested.put(1,0.0); sLoad.put(1,new C(-injectionKw.getOrDefault(1,0.0)*1000/3,0));
        for(int b:buses)v.put(b,new C(base,0));
        for(Branch b:order) {
            double apparent=b.peakMva()*1000*cfg.demandScale();double p=apparent*cfg.pf(),q=apparent*Math.sqrt(1-cfg.pf()*cfg.pf());
            requested.put(b.to(),p);
            sLoad.put(b.to(),new C((servedFraction*p-injectionKw.getOrDefault(b.to(),0.0))*1000/3,servedFraction*q*1000/3));
        }
        double residual=Double.MAX_VALUE; int iterations=0; boolean converged=false;
        for(int it=1;it<=cfg.maxIterations();it++) {
            iterations=it; Map<Integer,C> summed=new HashMap<>(); boolean invalid=false;
            for(int b:buses) {
                if(v.get(b).abs()<base*0.01) {invalid=true;break;}
                summed.put(b,sLoad.get(b).div(v.get(b)).conj());
            }
            if(invalid)break;
            for(int j=order.size()-1;j>=0;j--) {
                Branch b=order.get(j); C current=summed.get(b.to());iBranch.put(b.id(),current);
                summed.put(b.from(),summed.get(b.from()).add(current));
            }
            Map<Integer,C> next=new TreeMap<>();next.put(1,new C(base,0));residual=0;
            for(Branch b:order) {
                C value=next.get(b.from()).sub(new C(b.rOhm(),b.xOhm()).mul(iBranch.get(b.id())));
                next.put(b.to(),value);residual=Math.max(residual,value.sub(v.get(b.to())).abs()/base);
            }
            v=next;
            if(!Double.isFinite(residual))break;
            if(residual<cfg.tolerancePu()){converged=true;break;}
        }
        List<LineResult> lineResults=new ArrayList<>();double loss=0,source=-injectionKw.getOrDefault(1,0.0),maxLoading=0;
        for(Branch b:order) {
            C current=iBranch.getOrDefault(b.id(),new C(0,0)); C send=v.get(b.from()).mul(current.conj());
            double kw=3*send.re/1000,kvar=3*send.im/1000;
            double l=3*current.abs()*current.abs()*b.rOhm()/1000;
            // Check both ends; current-equivalent rating uses nominal VLL and given MVA.
            double receiving=3*v.get(b.to()).abs()*current.abs()/1e6;
            double loading=100*Math.max(Math.max(Math.hypot(kw,kvar)/1000,receiving),Math.sqrt(3)*cfg.voltageKv()*current.abs()/1000)/b.limitMva();
            loss+=l;maxLoading=Math.max(maxLoading,loading);if(b.from()==1)source+=kw;
            lineResults.add(new LineResult(b.id(),b.from(),b.to(),kw,kvar,current.abs(),loading,l,kw<0?"REVERSE":"FORWARD",loading>100+1e-6));
        }
        List<BusResult> busResults=new ArrayList<>();double min=Double.MAX_VALUE,served=0,generated=0;
        for(int b:buses) {
            double pu=v.get(b).abs()/base,p=requested.get(b),inj=injectionKw.getOrDefault(b,0.0);
            min=Math.min(min,pu);served+=p*servedFraction;generated+=inj;
            busResults.add(new BusResult(b,pu,p,p*servedFraction,sLoad.get(b).im*3/1000,inj,pu<cfg.minPu()-1e-8||pu>cfg.maxPu()+1e-8));
        }
        double balance=source+generated-served-loss;
        converged=converged && Double.isFinite(balance) && Math.abs(balance)<0.001;
        return new Flow(converged,iterations,Double.isFinite(residual)?residual:Double.MAX_VALUE,balance,source,loss,min,maxLoading,
                (int)busResults.stream().filter(BusResult::violation).count(),(int)lineResults.stream().filter(LineResult::violation).count(),busResults,lineResults);
    }
    public boolean feasible(Flow f,Settings s) {
        return f.converged() && f.voltageViolations()==0 && f.loadingViolations()==0
                && f.sourceKw()<=s.sourceCapKw()+1e-6 && f.sourceKw()>=-s.exportCapKw()-1e-6;
    }
}
