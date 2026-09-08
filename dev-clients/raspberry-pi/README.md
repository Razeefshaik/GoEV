# Raspberry Pi Zero 2 W — EV client deployment

Everything here is prepared so that once the SD card arrives, getting the
Pi acting as a real EV on the network is a ~15 minute job. The Pi runs
`dev-clients/ev_client.py` unmodified (it's already pure Python 3 +
`paho-mqtt`, nothing Windows/PC-specific) — the server can't tell the
difference between it and the real thing, by design.

## 1. Flash the SD card

Use [Raspberry Pi Imager](https://www.raspberrypi.com/software/) (any OS):

1. Choose **Raspberry Pi OS Lite, 64-bit**. The Zero 2 W's quad-core
   Cortex-A53 (unlike the original single-core Zero) is fully 64-bit
   capable, so this is the recommended build, not the 32-bit one. No
   desktop needed; the Pi will run headless.
2. Click the gear icon (⚙) / "Edit Settings" **before** writing:
   - Set a hostname (e.g. `goev-ev`).
   - Enable SSH, "Use password authentication."
   - Set a username/password.
   - Configure your WiFi SSID/password — the Zero 2 W has WiFi built in
     (2.4GHz only), so no USB dongle or OTG adapter needed.
3. Write the image, then boot the Pi.

## 2. Find the Pi and connect

```bash
ping goev-ev.local        # or whatever hostname you set
```

If that doesn't resolve, check your router's connected-devices list for
its IP instead. Then:

```bash
ssh <username>@goev-ev.local
```

## 3. Get the code onto the Pi

Either clone the repo directly on the Pi (simplest if it has internet
access):

```bash
git clone <your-repo-url> ~/goev
cd ~/goev/dev-clients
```

...or copy just the `dev-clients/` folder from your PC:

```bash
scp -r dev-clients <username>@goev-ev.local:~/goev-dev-clients
```

## 4. Run the setup script

```bash
cd ~/goev/dev-clients      # or wherever you copied it
bash raspberry-pi/setup.sh
```

This installs `python3-pip`, creates a virtual environment at
`~/goev-venv`, and installs `paho-mqtt` into it.

## 5. Find your PC's LAN IP

The Pi needs to reach your PC's Mosquitto broker over the network —
`localhost` from the Pi's perspective means the Pi itself, not your PC.

```powershell
ipconfig            # look for "IPv4 Address" under your active adapter
```

Make sure `docker compose up mosquitto redis postgres` and `./gradlew
bootRun` are running on your PC, and that Windows Firewall allows inbound
connections on port 1883 (Docker's port mapping publishes it on
`0.0.0.0:1883`, so it's reachable from the LAN once the firewall allows it).

## 6. Run it once, by hand, to prove it works

```bash
~/goev-venv/bin/python ev_client.py --ev-id EV-PI --broker <your-pc-lan-ip> \
    --bus 6 --soc 60 --send-request --heartbeats 1
```

Watch `http://<your-pc-ip>:8080/` — you should see `EV-PI` show up on the
network diagram exactly like a request sent from the dashboard's own form
or from `dev-clients/demo.py` would. If it doesn't show up, it's almost
certainly the broker IP or firewall, not the code.

## 7. (Optional) Run continuously on boot

To have the Pi act as a persistent EV that keeps heartbeating without a
terminal open:

1. Edit `raspberry-pi/goev-ev-client.service` — change `--broker` to your
   PC's actual LAN IP, and `WorkingDirectory`/`ExecStart` paths if you put
   the code somewhere other than `/home/pi/goev/dev-clients`.
2. Install it:
   ```bash
   sudo cp raspberry-pi/goev-ev-client.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now goev-ev-client
   sudo systemctl status goev-ev-client      # confirm it's running
   journalctl -u goev-ev-client -f            # watch its logs live
   ```

## Later: wiring in real sensors

Right now `--soc` and `--bus` are CLI arguments you supply by hand. When
you're ready to read real hardware (a voltage divider for battery level, a
GPS module for location, a button for "send request now"), the only thing
that changes is `dev-clients/ev_client.py`'s `build_status_payload`/
`build_request_payload` functions — replace the `args.soc`/`args.bus`
lookups with actual sensor reads. The MQTT topics, payload shape, and
everything server-side stay exactly the same; that's the whole point of
having kept this compatible from day one.

## Why minimal Pi usage is the right call

Everything above is a ~15 minute one-time setup. Day-to-day development
(routing logic, dashboard, scoring, batch optimizer) should keep happening
against `dev-clients/ev_client.py`/`evcs_client.py`/`demo.py` run on your
PC — those are exact stand-ins. Reach for the actual Pi only when you
specifically want to confirm "does this work on real hardware," not for
every code change. That's lower-risk for the hardware (SD card wear from
constant reboots/reflashing) and faster for you (no SSH round-trip per
test).
