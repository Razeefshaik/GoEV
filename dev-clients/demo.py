#!/usr/bin/env python3
"""
A scripted, narratable demo run: registers a few charging stations, then
sends a handful of EV charge requests that showcase feasibility filtering
(connector matching), a successful greedy assignment, and a rejection -
each against the real running server over real MQTT, nothing mocked.

Run this against a live stack:
    docker compose up mosquitto redis postgres
    ./gradlew bootRun
    (in another terminal) python dev-clients/demo.py

Then open http://localhost:8080/ before or during the run to watch the
network diagram update live.
"""
import subprocess
import sys
import time

HERE = subprocess.os.path.dirname(subprocess.os.path.abspath(__file__))


def step(title):
    print(f"\n{'=' * 70}\n{title}\n{'=' * 70}")
    time.sleep(1.5)


def run(script, *args):
    subprocess.run([sys.executable, subprocess.os.path.join(HERE, script), *args], check=True)


def main():
    step("1/4 - Registering three charging stations (heartbeats over MQTT)")
    # DOWNTOWN and INDUSTRIAL are CCS-only; SUBURB is CHAdeMO-only, so a
    # CHAdeMO vehicle later in the demo can only ever be routed to SUBURB,
    # even though INDUSTRIAL sits much closer to where it asks from.
    run("evcs_client.py", "--evcs-id", "EVCS-DOWNTOWN", "--bus", "6",
        "--connectors", "ccs", "--available-slots", "2", "--heartbeats", "1")
    run("evcs_client.py", "--evcs-id", "EVCS-INDUSTRIAL", "--bus", "17",
        "--connectors", "ccs", "--available-slots", "1", "--heartbeats", "1")
    run("evcs_client.py", "--evcs-id", "EVCS-SUBURB", "--bus", "24",
        "--connectors", "chademo", "--available-slots", "2", "--heartbeats", "1")
    time.sleep(1)

    step("2/4 - EV near the CCS station requests a charge (expect: assigned to EVCS-DOWNTOWN)")
    run("ev_client.py", "--ev-id", "EV-ALICE", "--bus", "7", "--soc", "45",
        "--ev-model", "compact_l2_ccs", "--max-detour-km", "20", "--send-request", "--heartbeats", "1")

    step("3/4 - EV whose vehicle only takes CHAdeMO requests right next to the industrial (CCS-only) station\n"
         "        (expect: connector filtering skips EVCS-INDUSTRIAL and routes to the farther EVCS-SUBURB instead)")
    run("ev_client.py", "--ev-id", "EV-BOB", "--bus", "18", "--soc", "60",
        "--ev-model", "compact_l2_chademo", "--max-detour-km", "20", "--send-request", "--heartbeats", "1")

    step("4/4 - EV with a very low SOC and a tiny max detour requests a charge\n"
         "        (expect: no feasible EVCS in reach -> rejection)")
    run("ev_client.py", "--ev-id", "EV-CARL", "--bus", "10", "--soc", "6",
        "--ev-model", "compact_l2_ccs", "--max-detour-km", "0.3", "--send-request", "--heartbeats", "1")

    print("\nDone. Check http://localhost:8080/ for the live network diagram,\n"
          "recent assignments table, and the animated assignment/rejection paths.")


if __name__ == "__main__":
    main()
