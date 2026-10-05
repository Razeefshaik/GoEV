package com.goev.v2g;

import static com.goev.v2g.Model.*;

public final class BatteryModel {
    public record Budget(double travelKwh,double arrivalSoc,double batteryKwh,double gridKwh,double maxHours) {}
    public Budget budget(Ev ev,double distanceKm,double efficiency,double gridKw) {
        positive(ev.usableKwh(),"usableKwh"); nonnegative(ev.soc(),"soc"); nonnegative(ev.reserveSoc(),"reserveSoc");
        if(ev.soc()>100 || ev.reserveSoc()>100) throw new IllegalArgumentException("SOC outside [0,100]");
        nonnegative(ev.consumptionKwhKm(),"consumption"); nonnegative(distanceKm,"distanceKm");
        nonnegative(ev.onwardKwh(),"onwardKwh"); positive(efficiency,"efficiency");
        if(efficiency>1) throw new IllegalArgumentException("efficiency > 1");
        nonnegative(gridKw,"gridKw");
        double travel=distanceKm*ev.consumptionKwhKm();
        double arrival=ev.soc()-100*travel/ev.usableKwh();
        double available=ev.usableKwh()*(arrival-ev.reserveSoc())/100-ev.onwardKwh();
        if(available < -1e-9) throw new IllegalArgumentException("travel breaches owner/onward reserve");
        available=Math.max(0,available);
        return new Budget(travel,arrival,available,efficiency*available,gridKw==0?0:efficiency*available/gridKw);
    }
}
