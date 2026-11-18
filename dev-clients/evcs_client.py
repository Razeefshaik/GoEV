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
import sqlite3
import threading
from datetime import datetime, timezone

import paho.mqtt.client as mqtt


def build_status_payload(args):
    committed = getattr(args, 'committed_reservations', [])
    return {
        "evcsId": args.evcs_id,
        "busId": args.bus,
        "connectorTypes": args.connectors.split(","),
        "availableSlots": max(0, args.available_slots-len(committed)),
        "totalSlots": args.total_slots,
        "committedReservationIds": committed,
        "queueLength": args.queue_length,
        "chargeRateKw": args.charge_rate_kw,
        "currentLoadKw": args.current_load_kw,
    }


def on_connect(client, userdata, flags, reason_code, properties=None):
    print(f"[{userdata['evcs_id']}] connected: {reason_code}")
    client.subscribe(f"evcs/{userdata['evcs_id']}/command", qos=1)


def on_message(client, userdata, msg):
    try:
        if msg.topic != f"evcs/{userdata['evcs_id']}/command":
            raise ValueError('topic identity mismatch')
        command=json.loads(msg.payload)
        if command['evcsId'] != userdata['evcs_id']:
            raise ValueError('station identity mismatch')
        key=command['reservationId']+'|'+command['command']
        with userdata['lock'], userdata['db']:
            previous=userdata['db'].execute('select result from commands where id=?',(key,)).fetchone()
            if previous:
                result=previous[0]
            else:
                result='ACCEPTED'
                if command['command']=='RESERVE':
                    count=userdata['db'].execute('select count(*) from reservations').fetchone()[0]
                    if count >= userdata['args'].available_slots:
                        result='REJECTED'
                    else:
                        userdata['db'].execute('insert or ignore into reservations values (?,?)',
                            (command['reservationId'],command['expiresAt']))
                elif command['command']=='RELEASE':
                    userdata['db'].execute('delete from reservations where id=?',(command['reservationId'],))
                else:
                    result='REJECTED'
                userdata['db'].execute('insert into commands values (?,?)',(key,result))
        ack={'evcsId':userdata['evcs_id'],'reservationId':command['reservationId'],
             'commandType':command['command'],'result':result,'timestamp':datetime.now(timezone.utc).isoformat()}
        client.publish(f"evcs/{userdata['evcs_id']}/ack",json.dumps(ack),qos=1)
        print(f"[{userdata['evcs_id']}] simulated command: {ack}")
    except Exception as error:
        print(f"Rejected command: {error}")


def main():
    parser = argparse.ArgumentParser(description="Simulated EVCS MQTT client")
    parser.add_argument("--evcs-id", required=True)
    parser.add_argument("--broker", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--bus", type=int, required=True, help="bus id this station is connected to")
    parser.add_argument("--connectors", default="ccs,chademo")
    parser.add_argument("--available-slots", type=int, default=2)
    parser.add_argument("--total-slots", type=int, default=2, help="explicit physical station capacity")
    parser.add_argument("--ledger", help="persistent simulation SQLite ledger; defaults to var/<station>.sqlite")
    parser.add_argument("--queue-length", type=int, default=0)
    parser.add_argument("--charge-rate-kw", type=float, default=50.0)
    parser.add_argument("--current-load-kw", type=float, default=0.0)
    parser.add_argument("--interval", type=float, default=5.0, help="seconds between heartbeats")
    parser.add_argument("--heartbeats", type=int, default=0, help="number of heartbeats to send before exiting (0 = forever)")
    args = parser.parse_args()
    if not 0 <= args.available_slots <= args.total_slots:
        parser.error('available slots must be within total physical capacity')
    from pathlib import Path
    path=Path(args.ledger or f"var/{args.evcs_id}.sqlite")
    path.parent.mkdir(parents=True,exist_ok=True)
    database=sqlite3.connect(path,check_same_thread=False)
    database.execute('create table if not exists commands (id text primary key,result text)')
    database.execute('create table if not exists reservations (id text primary key,expires text)')
    database.commit()
    lock=threading.Lock()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata={"evcs_id": args.evcs_id,'args':args,'db':database,'lock':lock})
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(args.broker, args.port)
    client.loop_start()

    count = 0
    try:
        while args.heartbeats == 0 or count < args.heartbeats:
            with lock,database:
                now=datetime.now(timezone.utc)
                for rid,expires in database.execute('select id,expires from reservations').fetchall():
                    if datetime.fromisoformat(expires.replace('Z','+00:00')) <= now:
                        database.execute('delete from reservations where id=?',(rid,))
                args.committed_reservations=[r[0] for r in database.execute('select id from reservations')]
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
