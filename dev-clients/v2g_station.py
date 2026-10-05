"""Deterministic MQTT station simulator. No inverter, BMS or hardware control."""
import argparse
import json
import sqlite3
from datetime import datetime, timezone


class StationSimulator:
    def __init__(self, station_id, database, ports=2, port_kw=20, site_kw=40):
        if ports < 1 or port_kw <= 0 or site_kw <= 0:
            raise ValueError("invalid equipment limits")
        self.station_id, self.ports, self.port_kw, self.site_kw = station_id, ports, port_kw, site_kw
        self.db = sqlite3.connect(database)
        self.db.execute("create table if not exists commands (id text primary key, ack text not null)")
        self.db.execute("create table if not exists sessions (id text primary key, run text, state text not null)")
        self.db.execute("create table if not exists ordering (run text primary key, sequence integer)")
        self.db.commit()

    def process(self, topic, message):
        if topic != f"v2g/v1/station/{self.station_id}/command" or message.get("version") != 1:
            raise ValueError("topic/version mismatch")
        command = message["command"]
        if command["stationId"] != self.station_id:
            raise ValueError("payload station mismatch")
        timestamp = datetime.fromisoformat(message["timestamp"].replace("Z", "+00:00"))
        if abs((datetime.now(timezone.utc) - timestamp).total_seconds()) > 120:
            raise ValueError("stale command timestamp")
        previous = self.db.execute("select ack from commands where id=?", (command["commandId"],)).fetchone()
        if previous:
            return json.loads(previous[0])
        run_id, session_id = message["runId"], command["sessionId"]
        ack = {"version": 1, "runId": run_id, "stationId": self.station_id,
               "commandId": command["commandId"], "result": "ACCEPTED"}
        with self.db:
            ordering = self.db.execute("select sequence from ordering where run=?", (run_id,)).fetchone()
            existing = self.db.execute("select state from sessions where id=?", (session_id,)).fetchone()
            state = json.loads(existing[0]) if existing else None
            states = [json.loads(row[0]) for row in self.db.execute("select state from sessions where run=?", (run_id,))]
            try:
                if ordering and command["sequence"] <= ordering[0]:
                    raise ValueError("out-of-order command")
                power = float(command["powerKw"])
                if not 0 <= power <= self.port_kw:
                    raise ValueError("per-port limit exceeded")
                kind = command["type"]
                if kind == "RESERVE":
                    if state or sum(s["status"] != "STOPPED" for s in states) >= self.ports:
                        raise ValueError("no available physical port / duplicate reservation")
                    if power + sum(s["reservedKw"] for s in states if s["status"] != "STOPPED") > self.site_kw + 1e-8:
                        raise ValueError("site commitment exceeded")
                    state = {"status": "RESERVED", "reservedKw": power, "actualKw": 0,
                             "soc": command["simulatedSoc"], "gridKwh": 0, "hour": command["simulationHour"]}
                elif kind == "START":
                    if not state or state["status"] != "RESERVED":
                        raise ValueError("start requires reservation")
                    state["status"] = "DISCHARGING"
                elif kind == "SETPOINT":
                    if not state or state["status"] != "DISCHARGING":
                        raise ValueError("setpoint requires active session")
                    if command["simulationHour"] < state["hour"] or command["simulatedDeliveredKwh"] < state["gridKwh"]:
                        raise ValueError("out-of-order meter state")
                    if not 0 <= command["simulatedSoc"] <= 100 or command["simulatedSoc"] > state["soc"] + 1e-8:
                        raise ValueError("invalid discharge SOC")
                    state.update(actualKw=power, soc=command["simulatedSoc"],
                                 gridKwh=command["simulatedDeliveredKwh"], hour=command["simulationHour"])
                elif kind == "STOP":
                    if not state:
                        raise ValueError("unknown session")
                    state.update(status="STOPPED", actualKw=0, reservedKw=0)
                else:
                    raise ValueError("unsupported command")
                self.db.execute("insert or replace into sessions values (?,?,?)", (session_id, run_id, json.dumps(state)))
                self.db.execute("insert or replace into ordering values (?,?)", (run_id, command["sequence"]))
                ack["simulatedTelemetry"] = state
            except (ValueError, KeyError) as error:
                ack.update(result="REJECTED", reason=str(error))
            self.db.execute("insert into commands values (?,?)", (command["commandId"], json.dumps(ack)))
        return ack


def main():
    import paho.mqtt.client as mqtt
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--station", default="V2G-1")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--database", default="v2g-station.sqlite")
    parser.add_argument("--ports", type=int, default=2)
    parser.add_argument("--port-kw", type=float, default=20)
    parser.add_argument("--site-kw", type=float, default=40)
    args = parser.parse_args()
    simulator = StationSimulator(args.station, args.database, args.ports, args.port_kw, args.site_kw)
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect = lambda c, u, f, reason, properties: c.subscribe(f"v2g/v1/station/{args.station}/command", qos=1)

    def receive(c, userdata, message):
        try:
            ack = simulator.process(message.topic, json.loads(message.payload))
            ack["timestamp"] = datetime.now(timezone.utc).isoformat()
            c.publish(f"v2g/v1/station/{args.station}/ack", json.dumps(ack), qos=1)
            print(json.dumps(ack), flush=True)
        except Exception as error:
            print(f"Rejected message: {error}", flush=True)
    client.on_message = receive
    client.connect(args.host, 1883, 30)
    client.loop_forever()


if __name__ == "__main__":
    main()
