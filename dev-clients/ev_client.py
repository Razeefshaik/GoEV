#!/usr/bin/env python3
"""
Simulated EV client — stands in for the Raspberry Pi device that will
eventually run this logic. Publishes heartbeat status and (optionally) a
charge request, and prints whatever assignment response comes back.

Payload shapes match the Java DTOs in com.goev.mqtt exactly (same field
names) so swapping this for the real device later is a no-op on the server.

Usage:
    python ev_client.py --ev-id EV-0231 --bus 6 --soc 18 --send-request
"""
import argparse
import json
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def build_status_payload(args):
    return {
        "evId": args.ev_id,
        "timestamp": now_iso(),
        "location": {"nodeRef": f"bus_{args.bus}", "offsetKm": args.offset_km},
        "socPercent": args.soc,
        "willingness": args.willingness,
    }


def build_request_payload(args):
    return {
        "requestId": str(uuid.uuid4()),
        "evId": args.ev_id,
        "timestamp": now_iso(),
        "location": {"nodeRef": f"bus_{args.bus}", "offsetKm": args.offset_km},
        "socPercent": args.soc,
        "batteryCapacityKwh": args.battery_capacity_kwh,
        "evModel": args.ev_model,
        "willingness": args.willingness,
        "maxDetourKm": args.max_detour_km,
    }


def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"[{userdata['ev_id']}] connected: {reason_code}")
    client.subscribe(f"ev/{userdata['ev_id']}/response", qos=1)


def on_message(client, userdata, msg):
    print(f"[{userdata['ev_id']}] response on {msg.topic}: {msg.payload.decode()}")


def main():
    parser = argparse.ArgumentParser(description="Simulated EV MQTT client")
    parser.add_argument("--ev-id", required=True)
    parser.add_argument("--broker", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--bus", type=int, default=1, help="nearest bus id for the simulated location")
    parser.add_argument("--offset-km", type=float, default=0.0)
    parser.add_argument("--soc", type=float, default=50.0)
    parser.add_argument("--willingness", default="no_passenger", choices=["has_passenger", "no_passenger"])
    parser.add_argument("--battery-capacity-kwh", type=float, default=62.0)
    parser.add_argument("--ev-model", default="compact_l2_ccs")
    parser.add_argument("--max-detour-km", type=float, default=5.0)
    parser.add_argument("--interval", type=float, default=5.0, help="seconds between heartbeats")
    parser.add_argument("--send-request", action="store_true", help="also publish a charge request after connecting")
    parser.add_argument("--heartbeats", type=int, default=0, help="number of heartbeats to send before exiting (0 = forever)")
    args = parser.parse_args()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata={"ev_id": args.ev_id})
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(args.broker, args.port)
    client.loop_start()

    if args.send_request:
        payload = build_request_payload(args)
        client.publish(f"ev/{args.ev_id}/request", json.dumps(payload), qos=1)
        print(f"[{args.ev_id}] sent charge request: {payload}")

    count = 0
    try:
        while args.heartbeats == 0 or count < args.heartbeats:
            payload = build_status_payload(args)
            client.publish(f"ev/{args.ev_id}/status", json.dumps(payload), qos=0)
            print(f"[{args.ev_id}] heartbeat: {payload}")
            count += 1
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
