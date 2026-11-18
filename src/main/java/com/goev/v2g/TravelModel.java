package com.goev.v2g;

import java.util.*;
import static com.goev.v2g.Model.*;

public interface TravelModel {
    double distanceKm(int from,int to,double offsetKm);
    /** Deterministic feeder surrogate, deliberately separate from the electrical solver. */
    final class FeederSurrogate implements TravelModel {
        private final Map<Integer,Map<Integer,Double>> edges=new TreeMap<>();
        public FeederSurrogate(List<Branch> lines) {
            for(Branch b:lines) {
                positive(b.lengthKm(),"lengthKm");
                edges.computeIfAbsent(b.from(),k->new TreeMap<>()).put(b.to(),b.lengthKm());
                edges.computeIfAbsent(b.to(),k->new TreeMap<>()).put(b.from(),b.lengthKm());
            }
        }
        public double distanceKm(int from,int to,double offsetKm) {
            if(offsetKm!=0) throw new IllegalArgumentException("nonzero location offset unsupported");
            if(!edges.containsKey(from) || !edges.containsKey(to)) throw new IllegalArgumentException("unknown travel bus");
            Map<Integer,Double> distances=new HashMap<>(); distances.put(from,0.0);
            PriorityQueue<Map.Entry<Integer,Double>> q=new PriorityQueue<>(Map.Entry.comparingByValue());
            q.add(Map.entry(from,0.0));
            while(!q.isEmpty()) {
                var at=q.poll(); if(at.getValue()>distances.get(at.getKey())) continue;
                if(at.getKey()==to) return at.getValue();
                for(var edge:edges.get(at.getKey()).entrySet()) {
                    double d=at.getValue()+edge.getValue();
                    if(d<distances.getOrDefault(edge.getKey(),Double.POSITIVE_INFINITY)) {
                        distances.put(edge.getKey(),d);q.add(Map.entry(edge.getKey(),d));
                    }
                }
            }
            throw new IllegalArgumentException("disconnected travel graph");
        }
    }
}
