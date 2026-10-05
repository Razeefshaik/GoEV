# GoEV neighbourhood V2G research prototype

This extension runs a **grid-connected**, balanced radial-network simulation.
An energized source remains available, subject to an explicit real-power cap.
EVs travel to compatible bidirectional stations and inject real power after
arrival. Missing supply and network restrictions produce explicitly reported
unserved load. This is a research simulation, not a blackout or hardware controller.

## Run and reproduce

The redesigned `/v2g` dashboard provides a network inspector, animated EV travel
and power-flow direction, generated Amtala map placement, per-EV battery cards,
eight explanatory comparison charts and a full-horizon three-policy lab.
Play advances the actual persisted simulation clock in selected 1/5/15-minute
steps; Pause stops future advances after any in-flight operation completes.
Vehicle motion interpolates along the feeder surrogate between calculated
steps and is not GPS telemetry. Cancellation updates the authoritative session.

The current-run charts compare its selected policy against the same-input,
no-EV baseline. Policy Lab creates three separate experiments (none, nearest,
coordinated) from the edited JSON and identical heartbeat setting, over the full
horizon; it does not advance the displayed live run. Equal results are reported
as ties. Initial battery budgets, remaining grid budget, battery discharge energy
and planned full travel energy are labelled separately. Loss comparisons also
report useful served energy rather than claiming that lower losses alone prove
improvement. A zero-ACK run remains an explicit software simulation.

Use Presentation view to hide the navigation rail, and Motion off or your OS
reduced-motion preference to disable animation. Interactive chart points, buses,
stations and vehicles support keyboard focus. Engineering tables and editable
JSON remain available in expandable sections. Export includes the live run,
current metrics and any completed policy comparisons.

The map reuses `seed/table1_bus_coordinates.csv` as a bundled static JSON file;
it uses the original Amtala anchor and generated bus positions, not surveyed
locations. No routes are requested from a road-routing service. Leaflet 1.9.4 is
bundled locally with its license, and OpenStreetMap tile attribution is visible.
Only the optional background tiles require internet. If tiles fail, the local
electrical schematic, charts and interactive map overlays remain available.
Regenerate coordinates after changing that seed file:

```powershell
Import-Csv src/main/resources/seed/table1_bus_coordinates.csv |
  ConvertTo-Json | Set-Content -Encoding utf8 src/main/resources/static/v2g/coordinates.json
```

Frontend verification: run an isolated research app on port 8082 with MQTT off,
then `python -B scripts/verify_dashboard.py` (Playwright and installed Edge).
It exercises numerical display agreement, selection, playback/pause, equivalent
policy experiments, cancellation, JSON download, mobile overflow, restart
restore, reduced motion and the no-shortage case. Evidence and screenshots are
written to `output/v2g/frontend/`. The historical backend validation record is
retained separately; frontend work does not regenerate numerical benchmarks.

Java 21 is required. From this original GoEV checkout, in PowerShell:

```powershell
$env:JAVA_HOME='C:\Users\RAZEEF\.jdks\corretto-21.0.5' # or your Java 21 installation
.\gradlew.bat researchRun
# Open http://localhost:8081/v2g
```

The ordinary application remains `gradlew.bat bootRun`, with `/` for charging
and `/v2g` for the research dashboard. It requires the existing PostgreSQL,
Redis and MQTT services (`docker compose up -d`). PostgreSQL is on host port
5433 in this checkout. Research mode uses neither PostgreSQL nor Redis.

```powershell
.\gradlew.bat test
python -m unittest discover -s dev-clients -p test_v2g_station.py
python scripts/regenerate_weasel.py
.\gradlew.bat v2gBenchmark
python -m pip install numpy scipy
python scripts/validate_v2g.py
python scripts/record_validation.py # after live smoke checks
```

On a machine with limited RAM, run Gradle tasks sequentially with:

```powershell
.\gradlew.bat test v2gBenchmark --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx192m -Xms32m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m'
```

`output/v2g/summary.csv`, `summary.json`, `comparison.html`, individual raw run
JSON files, `small-exact-reference.json`, and `independent-validation.json`
contain regenerated results. Full run JSON includes every time interval,
every bus/branch, input settings, sessions, rejection reasons and outbox.
Large raw runs are ignored by Git but remain locally available. Timing is
wall-clock Java computation time for assignment and full-horizon integration,
excluding JSON export; it is a single measured run, not a throughput guarantee.

## Architecture and state ownership

| Component | Responsibility |
|---|---|
| `ScenarioCatalog`, versioned CSV | Deterministic input scenarios and validation |
| `TravelModel` | Swappable distance interface; feeder surrogate implementation |
| `BatteryModel` | Travel, available energy and duration |
| `RadialPowerFlow` | Physical-unit backward/forward current sweep |
| `DispatchPolicy` | Constrained proportional load service and injection curtailment |
| `ResearchEngine` | Pair eligibility, fleet assignments, clock, SOC and lifecycle |
| `ResearchLedger` | Atomic durable research requests, sessions and command outbox |
| `ResearchController` | Same authoritative state for API and dashboard |
| `ResearchMqttBridge` | Optional versioned commands, ACKs, retries and support requests |
| `BenchmarkRunner`, independent Python validation | Reproducible comparisons |

Research runs are separate simulated universes. They do not reserve ports in
the legacy charging database, and their synthetic `V2G-*` stations must not be
treated as physical resources shared between runs. The file ledger is a local
single-host research store, not a distributed production database. JVM and OS
locks serialize read/admit/write, and writes fsync a temporary file before
atomic replacement. Reads reload persisted state; restart does not lose sessions,
operation IDs, simulated time, or pending commands. Corruption or unsupported
schema/atomic replacement fails the operation rather than resetting the ledger.
Default path: `var/v2g/ledger.json`; override `goev.v2g.ledger` for independent
experiments. Up to 50 retained runs are allowed. A new run resets its own simulation
without deleting prior runs. Existing databases and volumes are never cleared.

## Weasel correction and data provenance

The selected assumption is **ACSR Weasel**, replacing the Raccoon assumption
in the older reports for this extension. The official manufacturer table gives
0.9291 ohm/km at 20 C, nominal aluminium area 30 mm², aluminium area 31.61 mm²,
total area 36.88 mm², stranding 6/2.59 + 1/2.59 mm, overall diameter 7.77 mm,
mass 128 kg/km and breaking load 11.12 kN.

Source: [True Power ACSR specification](https://www.truepowergroup.in/acsr-conductors.html),
checked 5 October 2026. The mechanical breaking load is **not ampacity**.
Catalogue AC/DC basis and input resistance temperature are unconfirmed.
Weasel is the user's selected model conductor, not independently verified
installed-conductor evidence.

For a uniform conductor under matching conditions, `R = r L`; hence
`L_km = R_ohm / r_ohm_per_km`. Ohms divided by ohms/km gives km, with no
automatic three-phase factor. Original resistance sum is 34.923 ohms:

| Length model | Total km |
|---|---:|
| Original stored rounded CSV, approximately R/0.4 | 87.313 |
| Weasel version, R/0.9291 | 37.587988375847 |

`table1_lines.csv` stays unchanged. The reproducible
`table1_lines_weasel_v1.csv` changes **only inferred lengths**; R, X, local bus
demands and MVA limits are preserved. The accompanying provenance JSON records
checksums and parameters. The research loader reads the versioned resource
directly, so no stale PostgreSQL rows or legacy cached graph determine its
lengths. Legacy charging still uses its original stored graph; adopting revised
lengths there would require a separately reviewed database migration and graph
invalidation. Editing the original CSV alone would not refresh existing rows.

The two supplied PDFs are historical source reviews, not instructions or proof
of V2G implementation. Their Raccoon comparison is superseded here. Dataset
publication, conductor installation, voltage, PF, coincidence, transformer
parameters and surveyed coordinates remain unconfirmed. No IEEE feeder
attribution is inferred. The supplied local bus peaks sum to 1.082 MVA; root
headroom in a simultaneous-peak unity-PF scenario is `1.5 - 1.082 = 0.418 MVA`.
Summing individual peaks defines a scenario; it is not measured simultaneity.

## Input dictionary and defaults

All source/EV powers and load P/Q are three-phase totals. Capacity is **usable,
already health-adjusted**, so SOH is not applied again. A future nominal-capacity
input must be explicitly converted to usable capacity once.

| Input | Meaning / units |
|---|---|
| EV `id`, `bus` | Unique vehicle and discrete travel location; nonzero offsets unsupported |
| `usableKwh`, `soc`, `reserveSoc` | Usable energy capacity; percentages in [0,100] |
| `consumptionKwhKm`, `onwardKwh` | Constant travel consumption; extra reserved onward energy |
| `maxGridKw` | EV's maximum AC grid-side discharge output |
| `willing`, `bidirectional`, `connector` | Explicit consent and equipment compatibility |
| `departureHour`, `maxTravelKm`, `telemetryHour` | Simulated departure deadline, station-distance bound, last observation time |
| Station `bus`, `connectors`, `bidirectional` | Electrical connection, interfaces and discharge equipment |
| `totalPorts`, `externalOccupiedPorts` | Physical usable ports and independent existing occupancy |
| `portKw`, `siteKw` | Per-port and combined grid-side discharge limits |
| `online`, `telemetryHour` | Explicit availability; freshness tested against simulated clock |
| `voltageKv`, `pf` | Assumed line-line nominal voltage; demand PF |
| `sourceCapKw`, `exportCapKw` | Real source import and export limits |
| `minPu`, `maxPu` | Configurable voltage bounds, default 0.95 / 1.05 pu |
| `efficiency`, `speedKmH` | Grid output / battery energy efficiency; average travel speed |
| `horizonHours`, `stepHours` | Simulation horizon and maximum integration interval |
| `freshnessHours` | Maximum simulated telemetry age; future timestamps rejected |
| `tolerancePu`, `maxIterations` | Sweep convergence tolerance and iteration limit |
| `demandScale` | Constant load multiplier for this scenario |

Defaults are illustrative: 11 kV, PF 0.95, efficiency 0.90, speed 30 km/h,
3-hour horizon, 5-minute steps, 6-minute freshness, 1e-9 pu tolerance and 200
sweeps. Weak-voltage uses 8 kV as a sensitivity assumption. No-shortage uses
half the peak load and 1500 kW source cap; ordinary shortage uses full peak and
550 kW. Both policies within each scenario get **identical** inputs. Inputs can
be edited as JSON in the dashboard; no measured values are invented.

## Battery derivation and integration

At constant consumption, `Etravel = d e`. Removing that energy from usable
capacity C changes SOC by `100 Etravel/C`, so:

```
arrivalSoc = initialSoc - 100 * travelKwh / usableKwh
availableBatteryKwh = usableKwh * (arrivalSoc-reserveSoc)/100 - onwardKwh
deliverableGridKwh = efficiency * availableBatteryKwh
maxDurationHours = deliverableGridKwh / assignedGridKw
scheduledDurationHours = min(maxDurationHours, departureHour-etaHour,
                             horizonHours-etaHour)
deltaBatteryKwh = actualGridKw * dtHours / efficiency
socNext = socCurrent - 100 * deltaBatteryKwh / usableKwh
```

Example: C=60 kWh, arrival=70%, reserve=20%, efficiency=0.9, output=10 kW:
`available = 60(70-20)/100 = 30 kWh`; grid energy `0.9*30=27 kWh`;
maximum duration `27/10=2.7 h`. Zero assigned power yields zero support duration,
not infinity. Invalid capacities, SOC, efficiency and numeric values are rejected.
Travel/onward reserve failures are eligibility rejections. ETA is included;
the integrator splits at arrivals and ends and never injects during travel.
SOC while travelling is a linear simulated travel-energy estimate.

Actual variable setpoints are integrated interval by interval. Energy is
clamped to remaining deliverable energy, SOC respects owner plus onward
reserve, and estimates update after each interval. Maximum energy duration,
scheduled duration and remaining duration are separate fields. Final SOC and
delivered energy are simulated; neither is BMS or meter telemetry.

## Electrical equations and solver validation

Branch R and X are assumed per-phase ohms. Loads are balanced three-phase
constant-PQ demands with no shunts, phase imbalance, taps or neutral model.
`P_kW = 1000 S_MVA PF`, `Q_kvar = 1000 S_MVA sqrt(1-PF²)`.
Real EV injection is subtracted from P at the station bus; reactive support is
not assumed. `Vphase,base = 1000 VLL,kV / sqrt(3)`.

For phase-voltage phasor V and three-phase net complex power S:

```
I_load = conjugate((S_three_phase / 3) / V_phase)
I_branch = I_load_at_child + sum(child_branch_currents)
V_child = V_parent - (R + jX) * I_branch
S_send_three_phase = 3 V_parent conjugate(I_branch)
P_loss_kW = 3 |I_branch|² R / 1000
sourceKw + evInjectionKw = servedDemandKw + totalLossKw
```

KCL proves the backward current aggregation. Ohm's law gives the forward
voltage update. Complex-power definition and Joule heating give branch P/Q and
I²R losses. The source power balance includes all downstream demand and losses.
Branch loading checks sending/receiving apparent power and an additional
nominal-voltage current-equivalent MVA limit. This conversion is a model
assumption; it is not a catalogue Weasel ampacity claim.

Topology validation requires an oriented connected tree rooted at bus 1,
exactly one incoming branch per non-root, unique branch IDs and valid values.
The convergence residual is the maximum complex voltage change divided by
phase-voltage base. A result must converge and have absolute real-power balance
error below 0.001 kW before it is admitted. Non-convergence never means feasible.
The dashboard also exposes the unconstrained full-demand baseline, even when
it violates limits. Branch reverse flow is numerically signed and assumed
permitted; root export is separately bounded (zero by default). Unmodelled
transformer protection and island controls are not implied by this permission.

Analytical verification includes zero load and a two-bus purely resistive
feeder. With phase real power p and source voltage E,
`V²-EV+Rp=0`; the high-voltage root is
`V=(E+sqrt(E²-4Rp))/2`. Unit tests compare voltage and losses with this result.
Independent Python verification solves nodal KCL with SciPy's HYBR nonlinear
root algorithm, rather than repeating the Java sweep. Tolerances are 1e-7 pu
voltage, 1e-4 kW/kvar branch power, and 1e-5 kW losses. It also checks every
saved operating interval for convergence, constraints and energy balance.
This verifies numerical equations under shared assumptions, not physical data.

## Dispatch and assignment objective

The implemented policy is a deterministic **heuristic**, with no global
optimality claim. It first enforces consent, connector/capability, freshness,
ports, shared power, travel range, battery reserve and owner deadline. It tests
all remaining EV/station pairs with already committed generation included.

Electrical dispatch searches injection scale in 0.1 increments from 1 to 0.
For each scale it finds a feasible proportional served-load fraction using a
0.05 initial grid and 24 bisections within the highest located feasible
interval. It does not assume that zero load is feasible with large generation.
It maximizes served real demand; equivalent results prefer less generation.
The search is bounded and approximate, not a certified global nonlinear
optimizer. At every actual interval it rechecks combined injections against
all upstream branches, source import/export, bus voltages and equipment limits.

The coordinated pair ordering is estimated marginal served kW times scheduled
support hours (kWh), then shorter distance, then stable IDs. Nearest baseline
orders the **same eligible candidates** by distance. A pair with no electrical
service benefit is rejected. EVs are selected once per run; ports and station
power are conservatively committed for their entire scheduled window, including
travel, so overlapping assignments cannot exceed capacity. This does not
reuse freed ports to make new assignments within the same run.

Load shedding multiplies every bus's P and Q by the same fraction. There is
no critical-load priority implementation or claim. The system requires fully
feasible admitted points and sheds/curtails when necessary; it does not silently
relax voltage limits. Requested, served and unserved demand remain separate.
An import cap constrains the solver's result rather than merely relabeling an
unconstrained slack source. Even when support is insufficient, remaining
unserved energy stays visible. Equipment or reserve constraints never vanish.

## Session, operation and communications lifecycle

```
REQUESTED -> RESERVED -> EN_ROUTE -> ARRIVED -> DISCHARGING -> COMPLETED
```

Cancellation, expiry, failure and curtailment are supported terminal model
states. Admission failures are request/pair rejection explanations; selected
sessions maintain transition history. Repeating a transition is idempotent;
invalid transitions or reactivating terminal sessions are rejected. Cancellation
adds one STOP and terminal sessions no longer consume future simulation
commitments. Missing runtime heartbeats fail sessions closed. Simulated time
stops on application exit and resumes from the persisted clock; it is not a
wall-clock hardware watchdog. Owner deadlines/energy ends persist and reconcile
on further advancement. `simulatedHeartbeat=true` explicitly models continued
station freshness; false lets observations expire.

Request IDs deduplicate starts, operation IDs deduplicate ticks/cancellations,
and reuse with different bodies is rejected. File admission and outbox creation
are one locked durable replacement. MQTT happens afterwards. Each command ID
is persisted; failed sends or missing application ACKs retry it. Only the first
unacknowledged command per station/run is sent, preserving ordering. Device
SQLite remembers command results through restart. ACK station/topic identity,
version and wall-clock timestamp are checked; duplicate ACKs are harmless and
conflicting ACKs are rejected. This is application-level resource accounting,
not a claim that MQTT itself provides exactly-once delivery.

## API examples

`GET /api/v2g/scenarios` returns complete editable inputs. All run reads and
dashboard metrics use the same ledger.

```json
POST /api/v2g/runs
{"requestId":"demo-1","scenarioId":"moderate-shortage","policy":"coordinated"}

POST /api/v2g/runs/<runId>/advance
{"operationId":"tick-1","hours":0.25,"simulatedHeartbeat":true}

POST /api/v2g/runs/<runId>/cancel
{"operationId":"cancel-1","sessionId":"<sessionId>"}
```

Use `GET /api/v2g/runs/<runId>` and `/metrics` for current state, export, and
baseline comparisons. A POST start may include `scenario` instead of
`scenarioId`; the dashboard uses this form after editing JSON.

## Optional MQTT demonstration

Enable `goev.v2g.mqtt-enabled=true` (environment
`GOEV_V2G_MQTT_ENABLED=true`) and run a broker. Start one simulator per station:

```powershell
python -m pip install -r dev-clients/requirements.txt
python dev-clients/v2g_station.py --station V2G-1 --database var/v2g-1.sqlite
python dev-clients/v2g_station.py --station V2G-2 --database var/v2g-2.sqlite
python dev-clients/v2g_station.py --station V2G-3 --database var/v2g-3.sqlite
```

Equipment CLI limits must match the selected scenario. Commands use:
`v2g/v1/station/<stationId>/command`; ACKs use `/ack`.

Live API/browser/MQTT verification is reproducible with `python scripts/smoke_v2g.py`.
It expects the research app on 8081 with MQTT enabled and a Mosquitto test broker
on localhost:18884, plus `paho-mqtt` and Playwright (`pip install playwright`).
It uses the installed Microsoft Edge executable on this Windows machine. Start
the app with `GOEV_MQTT_BROKER_URL=tcp://localhost:18884` and an isolated
`GOEV_V2G_LEDGER` path for this check. Its station simulators are embedded in the
script; the standalone device commands above are for an interactive demonstration.
`record_validation.py` records actual XML test counts, validates identical inputs,
and requires successful live and independent evidence rather than assuming passes.

```json
{"version":1,"runId":"run-id","timestamp":"2026-10-05T12:00:00Z",
 "command":{"commandId":"unique-id","sessionId":"session-id",
 "stationId":"V2G-1","type":"SETPOINT","sequence":3,"powerKw":10,
 "simulationHour":0.5,"simulatedSoc":65,"simulatedDeliveredKwh":3}}

{"version":1,"runId":"run-id","stationId":"V2G-1","commandId":"unique-id",
 "result":"ACCEPTED","timestamp":"2026-10-05T12:00:01Z"}
```

RESERVE, START, SETPOINT and STOP change simulator SQLite state, ports,
discharge power and cumulative simulated SOC/energy; the ACK includes that
state. The simulator validates capacity and sequence and does not just print
commands. The authoritative electrical/energy simulation runs on the server;
device telemetry mirrors its estimates and does not validate actual hardware.
In accelerated mode the server does not gate discharge on wall-clock broker
ACKs. Missing devices retain pending outbox commands. This boundary is explicit.

Support requests also use `v2g/v1/support/<requestId>/request`:

```json
{"version":1,"requestId":"support-1","timestamp":"2026-10-05T12:00:00Z",
 "request":{"requestId":"support-1","scenarioId":"moderate-shortage","policy":"coordinated"}}
```

The result is published to `/assignment`. Timestamps in examples must be
replaced with current UTC timestamps. A Raspberry Pi can run these clients
using the existing deployment instructions and a reachable broker. It
demonstrates communications and software state, not BMS, inverter, protection,
anti-islanding, frequency/voltage reference or grid-forming capability.

## Legacy charging correctness changes

Charging admission now locks the persisted request, EV and station rows,
rechecks fresh reported availability, counts persisted commitments and
deduplicates request IDs. It models expiry as travel ETA plus estimated
charging duration using target SOC (80% research default), usable capacity and
EV/port/site power where known. Charging conversion efficiency is assumed 1
for this legacy duration estimate. Target, reserve and max-rate fields now
participate; no additional SOH factor is applied to usable capacity.

Arrival/finish are estimated wall-clock transitions, not measured charger
events. PostgreSQL assignments retain command-pending state. A scheduler
reconciles after restart and retries RESERVE/RELEASE until ACK. The reservation
code no longer calls the old Redis `occupySlot` timer helper; that deprecated
helper remains only for backward compatibility/tests. Heartbeats never own
server commitments. New heartbeat `totalSlots` is explicit, and
`committedReservationIds` permits subtracting device-reported owned reservations
before combining physical external occupancy with server commitments. Old
identity-free reports are handled conservatively and may reject usable capacity.
An unknown physical capacity is not inferred from a first available-slot count.

`evcs_client.py` now stores reservations and command deduplication in SQLite,
reports changing availability plus IDs/physical total, and acknowledges
commands. Use `--total-slots` consistently with `--available-slots`. Legacy
station registration already in PostgreSQL is preserved; capacity is enriched
only when missing. Existing confirmed historical records without EV/planning
metadata retain their old expiry until reconciliation; no data is deleted.

Additional corrections: unknown battery data no longer imply unlimited range;
unknown station load is not zero; queued-only stations cannot reserve a
nonexistent port; nonzero location offsets are rejected. Headroom aggregates
local coincident bus peaks downstream and remains an approximate unity-PF
**ranking** signal, not AC charging feasibility. Dijkstra is the Java path
algorithm; frontend ancestor reconstruction draws the unique tree path.
Timefold null assignments use `forEachIncludingUnassigned`, tested against
2.5.0; batch reason is `batch_joint_heuristic`. Legacy dashboard polling removes
expired telemetry and uses derived capacity, without refreshing heartbeat time.

## Metrics, comparisons and limitations

Benchmarks include no shortage, moderate shortage, weak voltage, multiple
stations, energy depletion, unwilling owners, incompatible equipment, site
bottleneck, insufficient travel reserve, departure limit, stale admission,
excess injection and insufficient support. Concurrency, repeated commands,
stale runtime, restart recovery, numerical failure and capacity invariants are
tested separately rather than fabricated as performance measurements.

For intervals i: requested/served/unserved energy is `sum(P_i dt_i)`, network
loss energy is `sum(lossKw_i dt_i)`, source energy is `sum(sourceKw_i dt_i)`,
and shortage duration is `sum(dt_i for unservedKw_i > 0.001)`. Fleet grid energy
is the sum of individual integrated delivered energy; battery consumption
divides by efficiency. Minimum voltage and maximum loading are extrema over
all admitted intervals. Maximum power-balance residual is reported explicitly.
Full-demand violations are exposed separately from admitted points, which have
zero voltage/loading violations by construction. Per-bus/per-branch values are
retained for independent calculations of violation duration or matched-service
loss studies. `pair_rejections` counts unique EV/station/reason evaluations,
not rejected support requests. Do not interpret that count as user acceptance.

V2G can serve more demand and increase absolute losses. The export makes served
energy explicit; lower losses due to load shedding are not presented as a V2G
gain. The small exact reference enumerates seven feasible static assignments
for two EVs and two single-port stations at fixed 20 kW. Its gap applies only
to that discrete arrived snapshot, not to the full time-dependent problem.

Unsupported: islanded backup, real charger/BMS control, surveyed road routes,
unbalanced flow, measured time-varying load profiles, critical-load priorities,
dynamic rescheduling after ports free, battery degradation compensation,
transformer/protection studies, regulatory compliance and globally optimal
fleet dispatch. Scenario inputs are configurable research assumptions. Physical
validation and production multi-host session admission require further work.
Check `output/v2g/validation-status.json` for actual current checks; Docker tests
that skip are not passes. No field or hardware validation is claimed.

## Verified results on 5 October 2026

The full Gradle suite passed 52 tests, zero failures/errors/skips, including
Testcontainers PostgreSQL, Redis and Mosquitto checks. A final targeted rerun
passed 15 checks, including two new API/page tests; these overlap the full suite
and must not be summed as 67 unique tests. Two Python state-machine checks passed.
Live HTTP/QoS1 checks acknowledged 17 commands and verified concurrent duplicate
starts, duplicate ticks, cancellation resource release and consistent snapshots.
Desktop/mobile browser checks passed with zero page errors; screenshots are in
`output/v2g`. Independent KCL validation passed four representative cases.

| Moderate shortage, 3 h | Without V2G | Coordinated V2G |
|---|---:|---:|
| Requested energy, kWh | 3083.700 | 3083.700 |
| Served energy, kWh | 1599.398 | 1700.323 |
| Unserved energy, kWh | 1484.302 | 1383.377 |
| Grid-delivered EV energy, kWh | 0 | 100.949 |
| Network loss energy, kWh | 50.602 | 50.625 |
| Minimum operating voltage, pu | 0.957358 | 0.956203 |

The nearest eligible policy matches coordinated service in this scenario. No
superiority claim follows from that tie. Higher served demand increases losses
slightly and lowers the operating minimum voltage slightly; both remain feasible.
The weak-voltage scenario has different results; all 39 policy/scenario runs are
exported. Independent maximum voltage difference was 2.182e-11 pu and maximum
branch P/Q difference 1.201e-7 kW/kvar. The seven-choice small static enumeration
found zero heuristic service gap for that toy case only. Raw timestamps, exact
values, settings and single-run timings are in the generated JSON/CSV.
