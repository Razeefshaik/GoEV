import unittest
import sqlite3
import threading
from types import SimpleNamespace
import json
from datetime import datetime, timezone
from v2g_station import StationSimulator


class StationTest(unittest.TestCase):
    def message(self, sequence, kind, session="S1", power=10, hour=0, soc=70, energy=0):
        return {"version": 1, "runId": "R1", "timestamp": datetime.now(timezone.utc).isoformat(),
                "command": {"commandId": str(sequence), "sequence": sequence, "sessionId": session,
                            "stationId": "V2G-1", "type": kind, "powerKw": power,
                            "simulationHour": hour, "simulatedSoc": soc, "simulatedDeliveredKwh": energy}}

    def test_lifecycle_deduplication_and_capacity(self):
        sim = StationSimulator("V2G-1", ":memory:", ports=1)
        topic = "v2g/v1/station/V2G-1/command"
        message = self.message(1, "RESERVE")
        first = sim.process(topic, message)
        self.assertEqual(first, sim.process(topic, message))
        self.assertEqual(sim.process(topic, self.message(2, "RESERVE", session="S2"))["result"], "REJECTED")
        self.assertEqual(sim.process(topic, self.message(3, "START"))["result"], "ACCEPTED")
        state = sim.process(topic, self.message(4, "SETPOINT", hour=1, soc=50, energy=10))["simulatedTelemetry"]
        self.assertEqual(state["gridKwh"], 10)
        self.assertEqual(state["soc"], 50)
        self.assertEqual(sim.process(topic, self.message(5, "STOP", power=0))["simulatedTelemetry"]["status"], "STOPPED")
        self.assertEqual(sim.process(topic, self.message(6, "RESERVE", session="S2"))["result"], "ACCEPTED")
        self.assertEqual(sim.process(topic, self.message(0, "START", session="S2"))["result"], "REJECTED")

    def test_legacy_charging_commands_change_capacity_and_acknowledge_once(self):
        from evcs_client import on_message, build_status_payload
        args=SimpleNamespace(evcs_id="ST",bus=2,connectors="ccs",available_slots=1,total_slots=1,
                             queue_length=0,charge_rate_kw=10,current_load_kw=0,committed_reservations=[])
        database=sqlite3.connect(":memory:")
        database.execute('create table commands (id text primary key,result text)')
        database.execute('create table reservations (id text primary key,expires text)')
        published=[]
        client=SimpleNamespace(publish=lambda topic,payload,qos: published.append(json.loads(payload)))
        userdata={'evcs_id':'ST','args':args,'db':database,'lock':threading.Lock()}
        def send(kind,rid):
            command={'evcsId':'ST','command':kind,'reservationId':rid,'expiresAt':'2026-12-01T00:00:00Z'}
            on_message(client,userdata,SimpleNamespace(topic='evcs/ST/command',payload=json.dumps(command).encode()))
        send('RESERVE','R1');send('RESERVE','R1')
        args.committed_reservations=[r[0] for r in database.execute('select id from reservations')]
        self.assertEqual(build_status_payload(args)['availableSlots'],0)
        self.assertEqual(len(args.committed_reservations),1)
        send('RESERVE','R2');self.assertEqual(published[-1]['result'],'REJECTED')
        send('RELEASE','R1');send('RELEASE','R1')
        self.assertEqual(database.execute('select count(*) from reservations').fetchone()[0],0)


if __name__ == "__main__":
    unittest.main()
