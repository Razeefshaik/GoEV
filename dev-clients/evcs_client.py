#!/usr/bin/env python3
"""
Simulated EVCS (charging station) client. Publishes status heartbeats and
prints any reserve/release commands the server sends. Payload shape matches
com.goev.mqtt.EvcsStatusMessage exactly.

Usage:
    python evcs_client.py --evcs-id EVCS-07 --bus 7 --connectors ccs,chademo
"""
import argparse
import json
import time

import paho.mqtt.client as mqtt


def build_status_payload(args):
    return {
        "evcsId": args.evcs_id,
        "busId": args.bus,
        "connectorTypes": args.connectors.split(","),
        "availableSlots": args.available_slots,
        "queueLength": args.queue_length,
        "chargeRateKw": args.charge_rate_kw,
        "currentLoadKw": args.current_load_kw,
    }


def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"[{userdata['evcs_id']}] connected: {reason_code}")
    client.subscribe(f"evcs/{userdata['evcs_id']}/command", qos=1)


def on_message(client, userdata, msg):
    print(f"[{userdata['evcs_id']}] command on {msg.topic}: {msg.payload.decode()}")


def main():
    parser = argparse.ArgumentParser(description="Simulated EVCS MQTT client")
    parser.add_argument("--evcs-id", required=True)
    parser.add_argument("--broker", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--bus", type=int, required=True, help="bus id this station is connected to")
    parser.add_argument("--connectors", default="ccs,chademo")
    parser.add_argument("--available-slots", type=int, default=2)
    parser.add_argument("--queue-length", type=int, default=0)
    parser.add_argument("--charge-rate-kw", type=float, default=50.0)
    parser.add_argument("--current-load-kw", type=float, default=0.0)
    parser.add_argument("--interval", type=float, default=5.0, help="seconds between heartbeats")
    parser.add_argument("--heartbeats", type=int, default=0, help="number of heartbeats to send before exiting (0 = forever)")
    args = parser.parse_args()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata={"evcs_id": args.evcs_id})
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(args.broker, args.port)
    client.loop_start()

    count = 0
    try:
        while args.heartbeats == 0 or count < args.heartbeats:
            payload = build_status_payload(args)
            client.publish(f"evcs/{args.evcs_id}/status", json.dumps(payload), qos=0)
            print(f"[{args.evcs_id}] heartbeat: {payload}")
            count += 1
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
