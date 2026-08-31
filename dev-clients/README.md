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

## Notes

- Both scripts loop forever by default; `--heartbeats N` sends N heartbeats
  then exits (handy for scripted tests). Ctrl+C stops either one cleanly.
- Run several instances (different `--ev-id`/`--evcs-id`) in separate
  terminals to simulate a small fleet against the same broker.
