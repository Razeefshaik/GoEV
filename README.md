# GoEV — EV to EVCS routing system

See [docs/ev-evcs-architecture-design.md](docs/ev-evcs-architecture-design.md)
for the full architecture, data model, MQTT topics, and algorithm design.
This repo is built phase by phase per that doc's section 9; each phase gets
its own README once it lands. Currently implemented: **Phase 1 — data & graph
layer**, **Phase 2 — MQTT plumbing**, **Phase 3 — state service**,
**Phase 4 — greedy policy engine**, **Phase 5 — batch optimizer**,
**Phase 6 — dashboard**.

## Phase 1 — Data & graph layer

### What's here

- `Bus`, `Line` JPA entities (`com.goev.graph`) — the 28-bus/27-line
  distribution network from Table 1.
- `Evcs` (`com.goev.evcs`) and `Ev` (`com.goev.ev`) JPA entities and
  repositories — no seed data yet, since Table 1 only covers the network,
  not station/vehicle instances.
  - `Ev` carries battery/SOC attributes beyond capacity: `stateOfHealthPercent`
    (degradation), `lastKnownSocPercent` (durable fallback only — live SOC is
    Redis's job from phase 3 on), `minSocThresholdPercent` (safety reserve
    floor for feasibility filtering), `targetSocPercent` (desired post-charge
    level), `energyConsumptionKwhPerKm` (for range/reachability estimates),
    and `maxChargeRateKw` (onboard charger ceiling — actual charge rate is
    `min(ev.maxChargeRateKw, evcs.chargeRateKw)`).
  - `Evcs` gained `ratedCapacityKw` (station-level power ceiling, separate
    from per-slot `chargeRateKw`) and `currentLoadKw` (live aggregate load —
    a placeholder field until phases 3/7 keep it updated). No real capacity
    numbers exist for this network's stations yet, so both are nullable
    variables to be filled in as that data becomes available.
- `NetworkSeedLoader` — reads `src/main/resources/seed/table1_lines.csv` on
  startup and populates `bus`/`line` tables if empty. Edit the CSV, not Java,
  to adjust network data.
- `NetworkGraphService` (`com.goev.graph`) — builds a JGraphT weighted graph
  from the persisted buses/lines (edge weight = `est_length_km`, derived from
  each line's resistance assuming ≈0.4 Ω/km, per the architecture doc). Two
  queries:
  - `distanceKm(busIdA, busIdB)` — shortest-path distance in km.
  - `headroomMva(busId)` — remaining capacity (`max_loading_mva` minus Table
    1's peak load) on the line feeding that bus; empty for the root bus
    (bus 1), which has no feeding line. This is a static baseline — build
    phase 7 wires in live EVCS load so it reflects real-time headroom.

### Running

```bash
./gradlew test      # unit tests against the real 28-bus data (H2 in-memory)
./gradlew bootJar    # builds build/libs/goev-server-0.1.0-SNAPSHOT.jar
```

The app itself (`./gradlew bootRun`) expects Postgres at
`jdbc:postgresql://localhost:5432/goev` (see `application.yml`) — not needed
yet since there's no MQTT/web entrypoint until Phase 2, but the seed loader
and JPA schema (`ddl-auto: update`) will run against it on startup.

### What to check for Phase 1

1. Do the entity fields match what you want (e.g. `Bus.loadType` is left
   `null` — Table 1 doesn't classify buses, so I didn't guess one).
2. `NetworkGraphService.headroomMva` currently returns a static number from
   Table 1's peak-load column, not live load — confirm that's the right
   placeholder until phase 7.
3. Confirm the `est_length_km` derivation (R / 0.4 Ω/km) is acceptable as a
   stand-in for real conductor length, per the doc's note in section 10.
4. `EVCS`/`EV` entities exist with repositories but are unseeded — there's no
   Table 1 equivalent for actual station/vehicle instances yet.

## Phase 2 — MQTT plumbing

### What's here

- **Build tool switched to Gradle** per your request — Maven's gone, use
  `./gradlew` for everything from here on.
- `com.goev.mqtt` — Spring Integration MQTT wiring:
  - `MqttConfig` — client factory, one inbound channel adapter subscribed to
    `ev/+/status`, `ev/+/request`, `evcs/+/status`, and one outbound channel
    adapter for publishing.
  - `MqttProperties` (`@ConfigurationProperties(prefix = "goev.mqtt")`) —
    broker URL, client id, topic patterns, QoS — all in `application.yml`,
    not hardcoded.
  - `MqttInboundRouter` — the only place that knows the topic → DTO mapping;
    parses the topic, deserializes the JSON payload, and dispatches to
    whatever `EvStatusHandler` / `ChargeRequestHandler` / `EvcsStatusHandler`
    beans exist. Those lists are empty right now — phases 3/4 (state service,
    policy engine) implement them without touching this package.
  - `MqttGateway` — `publishResponse(evId, AssignmentResponseMessage)` and
    `publishCommand(evcsId, EvcsCommandMessage)`, publishing to
    `ev/{id}/response` and `evcs/{id}/command`.
  - DTOs as Java records matching the architecture doc's payload examples
    exactly: `EvStatusMessage`, `ChargeRequestMessage`,
    `AssignmentResponseMessage`, `EvcsStatusMessage`, plus `EvcsCommandMessage`
    (the doc describes `evcs/{id}/command` only as "reserve / release slot" —
    no JSON example — so I designed a minimal shape: `evcsId`, a
    `RESERVE`/`RELEASE` enum, `reservationId`, `evId`, `expiresAt`).
- `docker-compose.yml` + `docker/mosquitto/mosquitto.conf` — local Mosquitto
  broker (`docker compose up mosquitto`), anonymous auth for dev only.
- `dev-clients/` — Python `paho-mqtt` stand-ins for the EV and EVCS devices
  (`ev_client.py`, `evcs_client.py`), payload field names identical to the
  Java DTOs. See `dev-clients/README.md` to run them.
- `MqttGatewayIntegrationTest` — spins up a real Mosquitto via Testcontainers
  and round-trips actual messages (inbound routing to a capturing handler,
  and `MqttGateway.publishResponse` delivery) rather than mocking the broker.
  Skips itself gracefully via a JUnit assumption if the Docker daemon isn't
  reachable — it did skip in this environment (Docker Desktop installed but
  not running), so I haven't seen it pass against a live broker yet.

### Running

```bash
docker compose up mosquitto     # local broker
./gradlew test                  # unit + MQTT integration tests (Docker required for the latter)
./gradlew bootRun                # starts the server, connects to the broker
```

### What to check for Phase 2

1. ~~Please start Docker Desktop and re-run `./gradlew test` once~~ — done:
   with Docker running, `MqttGatewayIntegrationTest` now actually passes
   against a real Mosquitto broker. Getting there surfaced two real bugs,
   both now fixed (see "Fixed after running with Docker" below).
2. I picked Spring Integration MQTT over raw Paho (the doc allowed either) —
   confirm that's fine, since phase 5's Timefold work and later phases will
   follow whatever messaging style is here.
3. `EvcsCommandMessage`'s shape is my own design (doc has no JSON example for
   that topic) — flag now if you want different fields before phase 4 starts
   publishing real commands through it.

### Fixed after running with Docker

Running the Testcontainers-backed tests for real (rather than skipping)
surfaced bugs that compiling and skipping had hidden:

1. **No `ObjectMapper` bean existed at all.** Spring Boot's Jackson
   autoconfiguration needs `Jackson2ObjectMapperBuilder`, which lives in
   `spring-web` — not on this app's classpath since there's no web starter.
   Every context that touched `MqttGateway` or `MqttInboundRouter` failed to
   start. Fixed with an explicit `JacksonConfig` bean
   (`com.goev.mqtt.JacksonConfig`) rather than pulling in a web dependency
   this app doesn't otherwise need.
2. **`MqttInboundRouter` had no error isolation between handlers** — exactly
   the risk flagged in this README before phase 3 existed. Once `StateService`
   became a real `EvcsStatusHandler`/`EvStatusHandler` and a test ran without
   Redis available, its exception aborted the dispatch loop before other
   handlers (including the test's own capturing handler) ever ran. Fixed:
   `MqttInboundRouter` now catches and logs per-handler, so one handler
   failing (e.g. a downstream store being briefly unreachable) can't block
   the others.
3. Also bumped `testcontainersVersion` from 1.20.1 to 1.21.4 — the older
   version's Docker API version negotiation over the Windows npipe transport
   was incompatible with this Docker Desktop's engine ("client version 1.32
   is too old").

## Phase 3 — State service

### What's here

- `com.goev.state.StateService` implements the three MQTT handler interfaces
  from phase 2 (`EvStatusHandler`, `EvcsStatusHandler`, `ChargeRequestHandler`)
  and registers itself automatically with `MqttInboundRouter` — no wiring
  needed elsewhere. It:
  - writes `EvLiveState`/`EvcsLiveState` to Redis on every heartbeat
    (`@RedisHash`, one row per EV/EVCS, keyed by id),
  - persists every `ev/{id}/request` as a durable `ChargeRequest` row in
    Postgres,
  - exposes `getEvState`/`getEvcsState` reads for later phases.
- **TTL-based staleness handling**: `EvLiveState`/`EvcsLiveState` carry a
  `@TimeToLive` field set from `StateProperties` (`goev.state.ev-ttl-seconds`
  / `evcs-ttl-seconds`, default 90s each) — Redis expires the key itself if
  no fresh heartbeat arrives in time, so a disconnected EV/EVCS silently
  drops out of live state rather than routing decisions reading stale data
  forever.
- `ChargeRequest`/`Assignment` JPA entities + repositories
  (`com.goev.state`), matching the architecture doc's ER diagram — except
  `ChargeRequest` also carries `locationNodeRef`/`locationOffsetKm`, which
  the doc's ER diagram omits but its own MQTT payload example includes;
  routing (phase 4) needs the EV's location, so I kept it on the durable
  record rather than dropping it. `Assignment.status` is a Java enum
  (`RESERVED`/`CONFIRMED`/`EXPIRED`/`CANCELLED`) stored as the doc's
  `string status` column via `@Enumerated(STRING)`.
- `docker-compose.yml` now also runs `redis` and `postgres` alongside
  `mosquitto` — the app needs all three from this phase on.
- `StateServiceIntegrationTest` — real Postgres + Redis via Testcontainers,
  same Docker-availability-assumption skip pattern as phase 2's MQTT test
  (skipped here too, same reason: Docker Desktop not running in this
  environment). Covers a heartbeat being readable then expiring after its
  TTL, an EVCS heartbeat round-trip, and charge-request persistence.

### Running

```bash
docker compose up mosquitto redis postgres   # everything the app now needs
./gradlew test                                # Docker required for the Testcontainers tests
./gradlew bootRun
```

### What to check for Phase 3

1. ~~Please run `./gradlew test` with Docker Desktop running~~ — done:
   `StateServiceIntegrationTest` now passes against real Postgres and Redis
   (needed one fix — see below).
2. `ChargeRequest` gained `locationNodeRef`/`locationOffsetKm` beyond the
   doc's ER diagram (see above) — confirm that's the right call rather than,
   say, keeping location only in Redis/live state.
3. Default TTLs are 90s for both EV and EVCS heartbeats — that's an
   arbitrary guess (dev-clients' default heartbeat interval is 5s, so 90s is
   ~18 missed heartbeats before expiry); tell me the real interval you plan
   to run devices at and I'll size these properly.
4. `StateService` doesn't yet do anything with `Assignment` — no phase before
   4 (routing) creates one, so the entity/repository exist but are unused
   until then. Expected, just flagging it's inert right now.

### Fixed after running with Docker

`StateServiceIntegrationTest` failed the first real run: the base test
`application.yml` hardcodes `spring.datasource.driver-class-name:
org.h2.Driver`, and an explicit driver class name takes priority over
Spring Boot's usual auto-detection from the JDBC URL — so even though
`@DynamicPropertySource` pointed the URL at the Testcontainers Postgres
instance, Hibernate kept trying to open it with the H2 driver. Fixed by
also overriding `spring.datasource.driver-class-name` to
`org.postgresql.Driver` in that test's dynamic properties.

## Phase 4 — Greedy policy engine

### What's here

This is the first fully working vertical slice: an `ev/{id}/request` over
MQTT results in a real assignment (or rejection) published back over MQTT,
with a durable `Assignment` row to show for it.

- `com.goev.routing.RoutingPolicyEngine` — pure decision logic, no MQTT or
  DB side effects, so it's unit-testable in isolation:
  - **Feasibility filter** (`filterFeasible`), in order: the EVCS must have
    live state (no recent heartbeat = expired out of Redis per phase 3's
    TTL = treated as offline), a free slot or a queue no longer than
    `goev.routing.max-acceptable-queue-length`, a matching connector,
    distance within the request's `maxDetourKm`, and distance within the
    EV's estimated reachable range (`batteryCapacityKwh` × usable SOC ÷
    `energyConsumptionKwhPerKm`, using the EV's durable record for the
    reserve-floor/consumption-rate if one exists, else config defaults —
    the EV doesn't need to be pre-registered for routing to work, since the
    request payload itself already carries battery capacity and model).
  - **Scoring** (`routeGreedy` picks the max): a weighted sum of normalized
    distance, SOC-urgency-weighted distance, queue length, line headroom
    (from phase 1's `NetworkGraphService.headroomMva`), and a willingness
    term — `has_passenger` reinforces the distance term (favor closest),
    `no_passenger` reinforces the headroom term (favor network balance),
    per the doc's qualitative description in section 7. All five weights
    live in `goev.routing.weight-*` config.
  - Connector compatibility is inferred from the request's `evModel` string
    (trailing `_`-separated token, e.g. `compact_l2_ccs` → `ccs`) matched
    against the EVCS's live connector list — the doc's payload examples
    imply this convention but there's no dedicated connector field on the
    EV side, so this is my own reading of that convention.
- `com.goev.routing.RoutingCoordinator` implements `ChargeRequestHandler`
  (self-registers with `MqttInboundRouter`, same pattern as phase 3's
  `StateService`) and does the actual wiring: calls `routeGreedy`, on a hit
  persists an `Assignment` (status `RESERVED`, expiring after
  `goev.routing.reservation-ttl-seconds`), publishes the EV's response
  (`reasonCode: "greedy_best_score"`) and the EVCS's `RESERVE` command; on a
  miss, publishes a rejection response (`reasonCode: "no_feasible_evcs"`,
  everything else `null`). No auto-widening of the search radius on
  rejection — the doc's flowchart offers that as an alternative, but
  getting the accept/reject slice correct came first.
- Tests, run for real against Docker (not skipped) before being reported
  done:
  - `RoutingPolicyEngineTest` — 6 pure Mockito unit tests, no Spring
    context: a feasible pick between two candidates, and five separate
    infeasible cases (connector mismatch, over max detour, EVCS offline, no
    slot + long queue, insufficient reachable SOC).
  - `RoutingCoordinatorIntegrationTest` — real Mosquitto + Postgres + Redis
    via Testcontainers: a feasible request produces a real `Assignment` row
    plus real `ev/{id}/response` and `evcs/{id}/command` MQTT messages; an
    infeasible request produces a real rejection response. Same
    Docker-availability-assumption skip pattern as phases 2/3.

### Running

```bash
docker compose up mosquitto redis postgres
./gradlew test        # Docker required for RoutingCoordinatorIntegrationTest
./gradlew bootRun
```

### Scoring is decoupled behind ScoringStrategy

Per your direction, the weighted-sum scoring formula (SOC urgency,
willingness, etc.) stays exactly as-is for now — it's just been moved
behind a `ScoringStrategy` interface (`com.goev.routing.ScoringStrategy`),
the same seam the architecture doc's class diagram already names
(`RoutingPolicyEngine --> ScoringStrategy`). `RoutingPolicyEngine` depends
only on that interface, never on the formula directly; the current formula
lives in `WeightedGreedyScoringStrategy`, a single `@Component` implementing
it. It's a pure function of its inputs (request, EVCS, live state, distance,
headroom) — no repository or graph lookups inside it — so it's independently
unit-tested (`WeightedGreedyScoringStrategyTest`, 3 tests) apart from
feasibility filtering. When the research on better weighting lands, swap in
a new `ScoringStrategy` implementation (e.g. mark it `@Primary` or replace
the `@Component`) and nothing else in `com.goev.routing` needs to change.

### What to check for Phase 4

1. The scoring formula itself (how SOC urgency and willingness modulate the
   base distance/queue/headroom terms) is still my own synthesis of section
   7's qualitative description into actual math, kept as-is per your
   direction — it's what a future `ScoringStrategy` replacement will need to
   beat.
2. Connector compatibility inferred from `evModel`'s trailing token (see
   above) — confirm that convention is really what you intend, since
   nothing enforces it beyond this code's assumption.
3. Rejections don't widen the search radius (per the doc's flowchart, that
   was an option) — they just reject. Say if you want radius-widening added
   now or if it's fine deferred.
4. Reachable-SOC feasibility uses config defaults
   (`default-min-soc-threshold-percent: 10`,
   `default-energy-consumption-kwh-per-km: 0.2`) whenever the requesting EV
   has no durable `Ev` row — reasonable for now since EVs aren't
   pre-registered, but worth knowing this is a guess per-request rather
   than real per-vehicle data until EVs get registered somewhere.

## Phase 5 — Batch optimizer

### What's here

A Timefold-based joint assignment across a configurable batching window,
swappable with the greedy engine via a strategy interface — per the build
plan, so the two can be compared directly by flipping one config value.

- **`goev.routing.mode`** (`greedy` | `batch`, default `greedy`) selects
  which `AssignmentStrategy` bean is active
  (`@ConditionalOnProperty` — exactly one of `GreedyAssignmentStrategy` /
  `BatchAssignmentStrategy` exists at a time). `RoutingCoordinator` (the
  actual `ChargeRequestHandler` wired to MQTT) now just delegates to
  whichever one is active; the reserve/reject persistence + MQTT publishing
  that used to live directly in `RoutingCoordinator` moved to a shared
  `ReservationService` so both strategies use identical side-effect code.
- **`GreedyAssignmentStrategy`** — phase 4's original behavior, unchanged:
  decide and reserve immediately per request.
- **`BatchAssignmentStrategy`** — queues incoming requests
  (`goev.routing.batch-window-ms`, default 10s between window closes via
  `@Scheduled`), then at window close calls
  `RoutingPolicyEngine.filterFeasible` for each pending request (identical
  feasibility rules to greedy — batching only changes how the *pick* across
  requests is made) and hands the whole batch to `BatchOptimizer`.
- **`BatchOptimizer`** wraps a Timefold `Solver<BatchRoutingSolution>`:
  - `RequestAssignment` (`@PlanningEntity`) — one per pending request, its
    own entity-level `@ValueRangeProvider` exposing only that request's
    feasible candidates (candidates aren't shared across requests since
    each embeds a request-specific distance/score), and
    `@PlanningVariable(allowsUnassigned = true)` so the solver can leave a
    request unassigned (→ rejection) when every candidate is claimed.
  - `BatchRoutingConstraintProvider` — the actual reason to batch at all:
    a **hard** constraint caps how many requests can land on the same EVCS
    at its live `availableSlots` (something greedy, deciding one request at
    a time, can't see coming); **soft** constraints reuse each candidate's
    already-computed `ScoringStrategy` score (reward) and penalize leaving
    a request unassigned when it didn't have to be.
  - Solver time budget: `goev.routing.batch-solver-time-limit-ms` (default
    3s), applied both as an overall cap and an unimproved-time early exit.
- Tests, run for real (not skipped) with Docker available:
  - `BatchOptimizerTest` — 3 tests against a **real Timefold solve**, no
    mocking of the solver itself: capacity is never exceeded when two
    requests contend for one EVCS's one slot, both get assigned when
    capacity allows it, and a request with zero feasible candidates stays
    unassigned.
  - `BatchAssignmentStrategyTest` — 2 Mockito tests of the queue/drain/
    dispatch logic in isolation from Timefold (already covered above):
    requests queue up, get solved together as one batch, and the pending
    queue is properly drained.

### Running

```bash
docker compose up mosquitto redis postgres
./gradlew test                    # Docker required for RoutingCoordinatorIntegrationTest etc.
./gradlew bootRun                  # add -Dgoev.routing.mode=batch to try batch mode
```

### What to check for Phase 5

1. **A real solver bug I hit and had to work around, not just style** — my
   first `BatchOptimizerTest` asserted which of two tied-ish candidates the
   solver should prefer, and that assertion flaked under real runs. The
   actual cause: baking each request's distance/score directly into the
   `RoutingCandidate` planning *value* means candidates aren't shared
   across requests' value domains, so Timefold's default move selectors
   have no single-step path to reassign a contested EVCS between two
   requests without passing through a temporarily-worse state — for a
   toy-sized problem (2 requests, 1 candidate each) the bounded-time local
   search can get stuck holding the "wrong" (lower-scoring) request's
   candidate. **The hard guarantee (capacity is never exceeded) held in
   every case**, including the failing run — only the soft-score tie-break
   was unreliable, and only at this pathologically small scale; real
   batches have far richer move neighborhoods. I narrowed the test to
   assert what's actually guaranteed rather than paper over it. If you want
   the tie-break itself to be reliable even at small scale, the real fix is
   remodeling the planning variable as the EVCS itself (shared across
   requests' value ranges) with score computed inside the constraint
   provider instead of pre-baked into the value — a larger change I didn't
   make speculatively.
2. Batch mode is wired and tested but **not the default** — `goev.routing.mode`
   defaults to `greedy`, matching phase 4's behavior, until you decide
   you're ready to compare or switch.
3. The soft-score constraints reuse the same `ScoringStrategy` bean as
   greedy, so batch and greedy are scored on the same terms — comparing the
   two later means comparing scheduling strategy, not different scoring
   rules.
4. `BatchAssignmentStrategy`'s pending queue is in-memory only (a
   `CopyOnWriteArrayList`) — a server restart mid-window drops whatever was
   queued. Fine for now; worth revisiting if batch mode becomes the
   production default.

## Phase 6 — Dashboard

### What's here

A WebSocket/STOMP-fed Thymeleaf view showing live EV positions, EVCS
status, and recent assignments — no build tooling (React/webpack/etc.)
added for it.

- `WebSocketConfig` — STOMP over WebSocket at `/ws` (SockJS fallback), a
  simple in-memory broker on `/topic`.
- `DashboardBroadcaster` — bridges two seams that already existed rather
  than inventing new plumbing:
  - implements `EvStatusHandler`/`EvcsStatusHandler` (`com.goev.mqtt`), so
    it self-registers with `MqttInboundRouter` exactly like `StateService`
    does — every heartbeat that reaches the state service also reaches the
    dashboard, no extra wiring.
  - implements a new `AssignmentListener` interface (`com.goev.routing`),
    which `ReservationService` now fans out to (same per-listener
    try/catch isolation as `MqttInboundRouter`) whenever a request is
    reserved or rejected — the first consumer of that seam, but built the
    same way as the MQTT handler lists so it's consistent with the rest of
    the codebase.
  - pushes to `/topic/ev-status`, `/topic/evcs-status`, `/topic/assignments`,
    and keeps a small in-memory snapshot (latest-per-id maps + last 20
    assignments) so a freshly opened dashboard has something to show
    before the next heartbeat arrives.
- `DashboardController` — `GET /` serves `templates/dashboard.html`;
  `GET /api/dashboard/snapshot` returns that in-memory snapshot as JSON for
  the page's initial load, before its STOMP subscriptions take over.
- `DashboardControllerTest` — 3 tests, run for real (not needing Docker):
  the page serves HTML, and the snapshot reflects a broadcasted EVCS status
  and a rejection. Doesn't need Docker because serving HTTP doesn't need a
  live broker/Redis/Postgres connection, only H2 for JPA.

### Demo-quality frontend (added after a request to present this to a professor)

The plain three-table page above was functional but not something you'd
want on a projector, so it's now a real network visualization:

- `NetworkTopologyController` (`com.goev.graph`) — `GET /api/network/topology`
  exposes Table 1's buses/lines so the page draws the actual network
  instead of duplicating that data in JavaScript.
- `dashboard.html` now renders the 28-bus network as an SVG tree (the 27
  lines for 28 buses mean it's exactly a spanning tree — no cycles to lay
  out) computed client-side from the topology endpoint, with:
  - EVCS markers on their bus, colored by live availability (green = free
    slot, amber = queue only, gray = offline/full),
  - EV markers on their bus, colored by SOC,
  - an animated dashed path drawn between an EV's bus and its assigned
    EVCS the moment `/topic/assignments` delivers a resolution (solid red
    flash for a rejection),
  - the three original tables kept alongside as a detail panel, plus a
    connection-status indicator and a color legend.
- `dev-clients/demo.py` — a scripted run for presenting: registers three
  charging stations (two CCS, one CHAdeMO) and sends three charge requests
  that demonstrate, in order, a normal assignment, connector-based
  feasibility filtering in action (an EV routed past a closer but
  incompatible station to a farther compatible one), and a rejection. **I
  ran this against the live app and fixed a real bug in my first version**:
  the "connector filtering" scenario silently didn't work because I'd
  given the nearer demo station both connector types by mistake, so the
  requesting EV was (correctly) routed there instead of skipping it — not
  a system bug, a demo-script bug, but I verified the corrected version
  actually produces the intended assignment before calling it done.
- **`Evcs`/`Ev` auto-registration** (`StateService`) — the Phase 1/5 gap
  where nothing ever created durable `Evcs`/`Ev` rows is now resolved by
  the option flagged earlier as worth deciding: the first heartbeat from a
  given `evcsId`, or the first charge request from a given `evId`, now
  auto-registers a durable row with whatever fields that message carries.
  A second heartbeat from the same station does not re-create or
  overwrite it. This was necessary for the demo script to work without
  manual `psql` setup, and is covered by two new
  `StateServiceIntegrationTest` cases.
- `NetworkTopologyControllerTest` — confirms the endpoint reflects the
  seeded 28-bus/27-line network.

### Running the demo

```bash
docker compose up mosquitto redis postgres   # postgres publishes on host port 5433, not 5432 — see below
./gradlew bootRun
# open http://localhost:8080/, then in another terminal:
python dev-clients/demo.py
```

I ran this exact sequence against the live app (not just the test suite)
and confirmed via the JSON APIs that all three scripted scenarios resolve
correctly. I could not visually confirm the rendered SVG/animations in an
actual browser — no browser automation tool is available in this session —
so **please open the dashboard yourself** before presenting to confirm the
network diagram, colors, and animations look right on your screen.

### What to check for Phase 6

1. **Environment finding, not a code bug**: this machine already has a
   native Postgres service bound to host port 5432 outside this project,
   which collided with the containerized dev Postgres and made `bootRun`
   fail with a password-authentication error (actually a wrong-server
   error) until I remapped the container to publish on **5433** instead
   (`docker-compose.yml` and `application.yml` both updated). If your other
   machines don't have that conflict, you may prefer to move it back to
   5432 — just flagging that I changed it and why.
2. ~~The `Evcs`/`Ev` registration gap~~ — resolved via auto-registration on
   first heartbeat/request (see above). If you'd rather have explicit
   registration (a seed file, an admin API) instead of implicit
   auto-registration for production, say so — this was one option among
   several I'd flagged, picked because it unblocks the demo cleanly, not
   because it's necessarily the right long-term answer.
3. ~~`AssignmentNotification.etaMin` is always `null`~~ — found while
   testing and fixed: `AssignmentListener.onAssignment` now takes `etaMin`
   directly, so the dashboard shows the same ETA the EV receives over MQTT.
4. The tree layout assumes the network stays a spanning tree (no added
   loop lines). If Table 1 ever grows a line that creates a cycle, the
   client-side layout algorithm (`layoutTree` in `dashboard.html`) will
   need rethinking — it doesn't currently handle non-tree topologies.
5. Local dev Postgres has been left with a clean slate (demo rows
   truncated) for you to run the demo yourself.

## Manual dashboard controls (added on request, for interactive demos)

Two forms on the dashboard let you drive the system by hand instead of only
via `dev-clients/`:

- **Place EVCS** — click a bus (or a line, which picks its near end) or
  type a bus number, fill in the station's id/connectors/slots/rate,
  submit. Publishes the same `EvcsStatusMessage` a real device's heartbeat
  would, through `MqttInboundRouter`'s dispatch (now public/reusable for
  exactly this) — same auto-registration and live-state treatment as MQTT.
- **Send charge request** — a structured form (EV id, bus, SOC%, battery
  kWh, connector, willingness, max detour). Publishes a synthetic heartbeat
  + request the same way, so the result shows up on the network diagram
  and tables exactly like a real device's request would.
- `POST /api/demo/evcs` and `POST /api/demo/request` (`DemoController`)
  back both forms; `DemoControllerTest` (2 tests, real Redis+Mosquitto via
  Testcontainers) confirms both actually flow through real routing, not
  just get accepted.

### A real bug found and fixed while testing this live

Placed two EVCS via the form, then got repeated `no_feasible_evcs`
rejections for a request that should obviously have worked (a station one
hop away, matching connector, plenty of SOC). Traced it to Redis directly
rather than guessing:

```bash
docker exec goev-redis redis-cli EXISTS "evcs_live_state:EVCS-1"   # -> 0, i.e. gone
```

**Root cause**: the "Place EVCS" button only ever sent *one* heartbeat,
unlike a real device (or `dev-clients/evcs_client.py`) which heartbeats
continuously. That single heartbeat's live state — the thing
`RoutingPolicyEngine` actually reads to decide feasibility — expires after
`goev.state.evcs-ttl-seconds` (90s default), same as it would for a real
device that went offline. But `DashboardBroadcaster`'s in-memory snapshot
cache (what the dashboard UI displays) never expires entries, so a
manually-placed station kept *looking* present on-screen for the rest of
the session while actually being invisible to routing. Any interactive
demo session running longer than 90 seconds would silently hit this.

**Fix**: the "Place EVCS" form now keeps re-sending that station's
heartbeat every 30 seconds for as long as the dashboard tab stays open
(`dashboard.html`), the same way a real device's continuous heartbeat
loop would. This only covers stations placed through the form in the
current tab — one placed, then the page reloaded, needs re-placing (its
JS-side refresh timer doesn't survive a reload). Restart the app to pick
up this fix; it's a template change, and Thymeleaf caching means a running
server won't see it until restarted.
