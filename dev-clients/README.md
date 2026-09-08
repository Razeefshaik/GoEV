# dev-clients

Python `paho-mqtt` stand-ins for the real EV (Raspberry Pi) and EVCS devices,
used to exercise the server during development. Payload field names match
the Java DTOs in `com.goev.mqtt` exactly, so pointing the real device at the
same topics later requires no server-side changes.

## Setup

```bash
cd dev-clients
python -m venv .venv
.venv\Scripts\activate        # or `source .venv/bin/activate` on macOS/Linux
pip install -r requirements.txt
```

## Start a local broker

From the repo root:

```bash
docker compose up mosquitto
```

## Run a simulated EV

```bash
python ev_client.py --ev-id EV-0231 --bus 6 --soc 18 --send-request
```

Publishes a heartbeat to `ev/EV-0231/status` every 5s (`--interval` to
change), optionally sends one charge request to `ev/EV-0231/request`
(`--send-request`), and prints any assignment that arrives on
`ev/EV-0231/response`.

## Run a simulated EVCS

```bash
python evcs_client.py --evcs-id EVCS-07 --bus 7 --connectors ccs,chademo
```

Publishes a heartbeat to `evcs/EVCS-07/status` every 5s and prints any
reserve/release command that arrives on `evcs/EVCS-07/command`.

## Run the scripted demo

```bash
docker compose up mosquitto redis postgres   # from the repo root
./gradlew bootRun                             # from the repo root, another terminal
python demo.py                                # from dev-clients/, another terminal
```

Registers three charging stations and sends three charge requests that
showcase, in order: a normal greedy assignment, connector-based feasibility
filtering (an EV routed past a closer but incompatible station to a farther
compatible one), and a rejection (SOC/detour too tight for anything to
qualify). Open `http://localhost:8080/` before or during the run to watch
the network diagram update live — EV/EVCS markers appear as heartbeats
arrive, and each assignment/rejection draws an animated path on the graph.
Rerun anytime; each EV/EVCS id is fresh so results don't depend on prior
runs (Postgres does keep durable rows across runs though — see the main
README's Phase 6 notes if you want a clean slate).

## Notes

- Both scripts loop forever by default; `--heartbeats N` sends N heartbeats
  then exits (handy for scripted tests). Ctrl+C stops either one cleanly.
- Run several instances (different `--ev-id`/`--evcs-id`) in separate
  terminals to simulate a small fleet against the same broker.
- A charge request only ever gets assigned to an EVCS that has a durable
  `Evcs` row — the server auto-registers one the first time it hears a
  heartbeat from a given `--evcs-id`, so just running `evcs_client.py`
  before `ev_client.py --send-request` is enough; no manual setup needed.

## Running on a real Raspberry Pi

See [raspberry-pi/README.md](raspberry-pi/README.md) — flashing the SD
card, headless setup, and deploying `ev_client.py` (unmodified) so a real
Pi Zero 2 W acts as an actual EV on the network instead of a PC-run
simulator.
