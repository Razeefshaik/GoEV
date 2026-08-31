# GoEV — EV to EVCS routing system

See [docs/ev-evcs-architecture-design.md](docs/ev-evcs-architecture-design.md)
for the full architecture, data model, MQTT topics, and algorithm design.
This repo is built phase by phase per that doc's section 9; each phase gets
its own README once it lands. Currently implemented: **Phase 1 — data & graph
layer**, **Phase 2 — MQTT plumbing**, **Phase 3 — state service**.

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

1. **Please start Docker Desktop and re-run `./gradlew test` once** — I
   couldn't verify `MqttGatewayIntegrationTest` actually passes against a
   real broker in this environment, only that it compiles and skips cleanly
   without Docker.
2. I picked Spring Integration MQTT over raw Paho (the doc allowed either) —
   confirm that's fine, since phase 5's Timefold work and later phases will
   follow whatever messaging style is here.
3. `EvcsCommandMessage`'s shape is my own design (doc has no JSON example for
   that topic) — flag now if you want different fields before phase 4 starts
   publishing real commands through it.
4. Inbound handler dispatch (`MqttInboundRouter`) fans out to all registered
   `XHandler` beans with no ordering/error-isolation guarantees yet — fine
   for now since no handlers exist, but worth revisiting once phase 3 adds
   real ones that must not break each other.

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

1. **Same ask as phase 2**: please run `./gradlew test` with Docker Desktop
   running at least once — `StateServiceIntegrationTest` has only been
   verified to compile and skip cleanly, not to actually pass against real
   Postgres/Redis.
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
