#!/usr/bin/env bash
# Run once on the Pi after first boot (over SSH) to get it ready to run the EV client.
# Usage: bash setup.sh
set -euo pipefail

echo "== Updating package lists =="
sudo apt-get update

echo "== Installing Python3 + pip (Raspberry Pi OS Lite doesn't always have pip preinstalled) =="
sudo apt-get install -y python3-pip python3-venv

echo "== Creating a virtual environment in ~/goev-venv =="
python3 -m venv ~/goev-venv

echo "== Installing paho-mqtt =="
~/goev-venv/bin/pip install --upgrade pip
~/goev-venv/bin/pip install paho-mqtt

echo
echo "Done. Run the client with:"
echo "  ~/goev-venv/bin/python ev_client.py --ev-id EV-PI --broker <your-pc-lan-ip> --bus 6 --soc 40 --send-request"
echo
echo "To install it as a systemd service that starts on boot, see goev-ev-client.service"
echo "in this same folder and README.md's 'Run continuously on boot' section."
