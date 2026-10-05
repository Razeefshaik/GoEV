package com.goev.v2g;

import java.util.*;

/** Research inputs: kW/kvar are three-phase totals; capacity is already usable/SOH-adjusted. */
public final class Model {
    private Model() {}
    public record Branch(int id, int from, int to, double rOhm, double xOhm,
                         double limitMva, double peakMva, double lengthKm) {}
    public record Ev(String id, int bus, double usableKwh, double soc, double reserveSoc,
                     double consumptionKwhKm, double maxGridKw, boolean willing, boolean bidirectional,
                     String connector, double departureHour, double maxTravelKm, double onwardKwh,
                     double telemetryHour) {}
    public record Station(String id, int bus, boolean bidirectional, Set<String> connectors,
                          int totalPorts, int externalOccupiedPorts, double portKw, double siteKw,
                          double telemetryHour, boolean online) {}
    public record Settings(double voltageKv, double pf, double minPu, double maxPu,
                           double sourceCapKw, double exportCapKw, double efficiency,
                           double speedKmH, double horizonHours, double stepHours, double freshnessHours,
                           double tolerancePu, int maxIterations, double demandScale) {
        public Settings {
            positive(voltageKv,"voltageKv"); positive(pf,"pf");
            if(pf>1) throw new IllegalArgumentException("pf > 1");
            positive(minPu,"minPu"); positive(maxPu,"maxPu");
            if(minPu>=1 || maxPu<=1) throw new IllegalArgumentException("voltage bounds must straddle slack 1 pu");
            nonnegative(sourceCapKw,"sourceCapKw"); nonnegative(exportCapKw,"exportCapKw");
            positive(efficiency,"efficiency"); if(efficiency>1) throw new IllegalArgumentException("efficiency > 1");
            positive(speedKmH,"speedKmH"); positive(horizonHours,"horizonHours"); positive(stepHours,"stepHours");
            positive(freshnessHours,"freshnessHours"); positive(tolerancePu,"tolerancePu"); positive(demandScale,"demandScale");
            if(maxIterations<1 || maxIterations>10000 || horizonHours>48 || stepHours<1.0/3600)
                throw new IllegalArgumentException("invalid simulation/iteration bounds");
        }
    }
    public record Scenario(String id, String description, List<Branch> branches, List<Ev> fleet,
                           List<Station> stations, Settings settings, String datasetSha256,
                           String travelModel, String conductor, double resistanceOhmKm, String operatingMode) {
        public Scenario {
            if(operatingMode==null)operatingMode="GRID_CONNECTED";
            if(!operatingMode.equals("GRID_CONNECTED"))throw new IllegalArgumentException("Islanded/outage operation is unsupported");
        }
    }
    public enum Status { REQUESTED, RESERVED, EN_ROUTE, ARRIVED, DISCHARGING, COMPLETED,
                         REJECTED, CANCELLED, EXPIRED, FAILED, CURTAILED }
    public static class Session {
        public String id, evId, stationId, reason;
        public int bus;
        public Status status=Status.REQUESTED;
        public double distanceKm, etaHour, arrivalSoc, reserveSoc, availableBatteryKwh,
            deliverableGridKwh, assignedKw, actualKw, maxDurationHours, scheduledDurationHours,
            startHour, endHour, actualStopHour, remainingHours, currentSoc, expectedFinalSoc,
            deliveredKwh, batteryUsedKwh, travelKwh;
        public List<Status> history = new ArrayList<>(List.of(Status.REQUESTED));
        public boolean terminal() { return Set.of(Status.COMPLETED,Status.REJECTED,Status.CANCELLED,
                Status.EXPIRED,Status.FAILED,Status.CURTAILED).contains(status); }
        public void transition(Status next) {
            if(status==next) return;
            if(terminal()) throw new IllegalArgumentException("terminal session " + id);
            boolean normal = switch(status) {
                case REQUESTED -> next==Status.RESERVED || next==Status.REJECTED;
                case RESERVED -> next==Status.EN_ROUTE;
                case EN_ROUTE -> next==Status.ARRIVED;
                case ARRIVED -> next==Status.DISCHARGING;
                case DISCHARGING -> next==Status.COMPLETED || next==Status.CURTAILED;
                default -> false;
            };
            if(!normal && !Set.of(Status.CANCELLED,Status.EXPIRED,Status.FAILED).contains(next))
                throw new IllegalArgumentException("invalid transition " + status + " -> " + next);
            status=next; history.add(next);
        }
    }
    public record BusResult(int bus, double voltagePu, double requestedKw, double servedKw,
                            double reactiveKvar, double injectionKw, boolean violation) {}
    public record LineResult(int id, int from, int to, double pKw, double qKvar,
                             double currentA, double loadingPercent, double lossKw,
                             String direction, boolean violation) {}
    public record Flow(boolean converged, int iterations, double residualPu, double balanceResidualKw,
                       double sourceKw, double lossesKw, double minVoltagePu, double maxLoadingPercent,
                       int voltageViolations, int loadingViolations, List<BusResult> buses,
                       List<LineResult> lines) {}
    public record Dispatch(double servedFraction, double injectionScale, double requestedKw,
                           double servedKw, double unservedKw, Flow flow) {}
    public record Sample(double hour, double durationHours, Dispatch baseline, Dispatch v2g,
                          double evKw, double deliveredKwh, double batteryUsedKwh) {}
    public static class Run {
        public String id, requestId, policy, createdAt, lastOperation;
        public Scenario scenario;
        public double hour;
        public List<Session> sessions = new ArrayList<>();
        public List<Sample> samples = new ArrayList<>();
        public List<String> rejections = new ArrayList<>();
        public Map<String,String> operations = new LinkedHashMap<>();
        public List<Command> outbox = new ArrayList<>();
        public Flow fullDemandBaseline;
        public Dispatch currentBaseline, currentV2g;
    }
    public static class Command {
        public String commandId, sessionId, stationId, type, acknowledgement;
        public double powerKw, simulationHour, simulatedSoc, simulatedDeliveredKwh;
        public int attempts;
        public int sequence;
        public boolean acknowledged;
    }
    public record Metrics(double requestedKwh, double servedKwh, double unservedKwh, double lossKwh,
                          double sourceKwh, double deliveredKwh, double batteryKwh, double travelKwh,
                          double shortageHours, double minVoltagePu, double maxLoadingPercent,
                          double maxBalanceResidualKw, int accepted, int rejected) {}
    public static void positive(double x,String name) {
        if(!Double.isFinite(x) || x<=0) throw new IllegalArgumentException(name+" must be finite and positive");
    }
    public static void nonnegative(double x,String name) {
        if(!Double.isFinite(x) || x<0) throw new IllegalArgumentException(name+" must be finite and nonnegative");
    }
}
