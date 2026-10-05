"""Live API, browser and QoS1 simulator checks. Requires research app and test broker.
Default API http://localhost:8081; broker localhost:18884. No real hardware.
"""
import json
import sys
import time
import uuid
import threading
import urllib.request
from pathlib import Path
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "dev-clients"))
sys.path.insert(0, str(ROOT / "var/verification-python"))
import paho.mqtt.client as mqtt
from v2g_station import StationSimulator
from playwright.sync_api import sync_playwright

OUTPUT = ROOT / "output/v2g"
OUTPUT.mkdir(parents=True, exist_ok=True)
ACKS, ERRORS, CLIENTS = [], [], []
BASE = "http://localhost:8081/api/v2g"


def api(path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as response:
        return json.load(response)


for i in range(1, 4):
    station_id = f"V2G-{i}"
    userdata = {"station": station_id, "simulator": None}
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata=userdata)

    def connected(c, data, flags, reason, properties):
        c.subscribe(f"v2g/v1/station/{data['station']}/command", qos=1)

    def receive(c, data, message):
        try:
            if data["simulator"] is None:
                data["simulator"] = StationSimulator(data["station"], ":memory:")
            ack = data["simulator"].process(message.topic, json.loads(message.payload))
            ack["timestamp"] = datetime.now(timezone.utc).isoformat()
            ACKS.append(ack)
            c.publish(f"v2g/v1/station/{data['station']}/ack", json.dumps(ack), qos=1)
        except Exception as error:
            ERRORS.append(str(error))
    client.on_connect, client.on_message = connected, receive
    client.connect("localhost", 18884, 30)
    client.loop_start()
    CLIENTS.append(client)

report = {}
try:
    request_id = "smoke-" + str(uuid.uuid4())
    body = {"requestId": request_id, "scenarioId": "moderate-shortage", "policy": "coordinated"}
    with ThreadPoolExecutor(max_workers=2) as pool:
        a, b = list(pool.map(lambda _: api("/runs", body), range(2)))
    assert a["id"] == b["id"], "duplicate run admission"
    run_id = a["id"]
    tick = {"operationId": "tick-1", "hours": .25, "simulatedHeartbeat": True}
    first = api(f"/runs/{run_id}/advance", tick)
    duplicate = api(f"/runs/{run_id}/advance", tick)
    assert first["hour"] == duplicate["hour"] == .25
    session_id = first["sessions"][0]["id"]
    cancelled = api(f"/runs/{run_id}/cancel", {"operationId": "cancel-1", "sessionId": session_id})
    cancelled = api(f"/runs/{run_id}/cancel", {"operationId": "cancel-2", "sessionId": session_id})
    assert cancelled["sessions"][0]["status"] == "CANCELLED"
    assert sum(c["type"] == "STOP" and c["sessionId"] == session_id for c in cancelled["outbox"]) == 1
    assert abs(sum(b["injectionKw"] for b in cancelled["currentV2g"]["flow"]["buses"])
               - sum(s["actualKw"] for s in cancelled["sessions"])) < 1e-8, "cancelled electrical snapshot"
    deadline = time.monotonic() + 50
    while time.monotonic() < deadline:
        current = api(f"/runs/{run_id}")
        if all(c["acknowledged"] for c in current["outbox"]):
            break
        time.sleep(.25)
    assert all(c["acknowledged"] for c in current["outbox"]), "outbox ACK timeout"
    assert all(c["acknowledgement"] == "ACCEPTED" for c in current["outbox"]), "station rejection"
    assert not ERRORS, ERRORS
    # A persistent server may also retry commands belonging to older runs.
    telemetry = [ack["simulatedTelemetry"] for ack in ACKS
                 if ack.get("runId") == run_id and "simulatedTelemetry" in ack]
    assert any(a["status"] == "DISCHARGING" for a in telemetry)
    assert any(a["gridKwh"] > 0 for a in telemetry)
    assert any(a["status"] == "STOPPED" for a in telemetry)
    report["api"] = {"passed": True, "runId": run_id, "concurrentDuplicateStart": True,
                     "duplicateTick": True, "cancelExactlyOnce": True}
    report["mqtt"] = {"passed": True, "commandsAcknowledged": len(current["outbox"]),
                      "simulatedMeterTransitions": True, "broker": "Mosquitto localhost:18884"}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, executable_path=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                                    args=["--disable-gpu", "--disable-extensions"])
        page = browser.new_page(viewport={"width": 1440, "height": 960})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto("http://localhost:8081/v2g", wait_until="networkidle")
        page.wait_for_selector("#scenario option[value='moderate-shortage']", state="attached")
        page.locator("#start").click()
        page.wait_for_function("document.querySelector('#sessions').rows.length > 1", timeout=90000)
        page.locator("#hours").select_option("0.25")
        page.locator("#advance").click()
        page.wait_for_function("document.querySelector('#clock').textContent.includes('0.250')", timeout=90000)
        page.wait_for_function("!busy && document.querySelector('#metrics').rows.length === 3", timeout=90000)
        assert page.locator("#buses tr").count() == 29
        assert page.locator("#lines tr").count() == 28
        assert page.locator("#error").inner_text() == ""
        page.screenshot(path=str(OUTPUT / "dashboard-desktop.png"), full_page=True)
        page.set_viewport_size({"width": 390, "height": 844})
        page.screenshot(path=str(OUTPUT / "dashboard-mobile.png"), full_page=True)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
        assert not errors, errors
        report["browser"] = {"passed": True, "pageErrors": errors, "busRows": 28, "lineRows": 27,
                             "startAndAdvance": True, "desktopAndMobile": True}
        browser.close()
finally:
    for client in CLIENTS:
        client.disconnect()
        client.loop_stop()
    (OUTPUT / "smoke-validation.json").write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
