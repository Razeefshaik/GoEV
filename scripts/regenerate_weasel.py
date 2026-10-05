"""Regenerate versioned inferred lengths without modifying original impedance/rating data."""
import csv
import hashlib
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / "src/main/resources/seed/table1_lines.csv"
destination = source.with_name("table1_lines_weasel_v1.csv")
rows = list(csv.DictReader(source.open(encoding="utf-8")))
output = io.StringIO(newline="")
writer = csv.DictWriter(output, fieldnames=rows[0].keys(), lineterminator="\n")
writer.writeheader()
for row in rows:
    row["est_length_km"] = f'{float(row["resistance_ohm"]) / 0.9291:.12f}'
    writer.writerow(row)
destination.write_text(output.getvalue(), encoding="utf-8", newline="")
metadata = {
    "datasetVersion": "table1-weasel-v1", "conductor": "ACSR Weasel",
    "selection": "User-selected research assumption; installed conductor not independently confirmed",
    "manufacturerUrl": "https://www.truepowergroup.in/acsr-conductors.html",
    "referenceDate": "2026-10-05", "resistanceOhmPerKm": 0.9291, "referenceTemperatureC": 20,
    "nominalAluminiumAreaMm2": 30, "aluminiumAreaMm2": 31.61, "totalAreaMm2": 36.88,
    "stranding": "6/2.59 aluminium + 1/2.59 steel", "diameterMm": 7.77,
    "massKgKm": 128, "breakingLoadKn": 11.12,
    "limitations": "Catalogue resistance AC/DC basis and input R temperature not established; breaking load is not ampacity",
    "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "versionedSha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
    "sumResistanceOhm": sum(float(row["resistance_ohm"]) for row in rows),
    "totalInferredLengthKm": sum(float(row["est_length_km"]) for row in rows),
    "migration": "Research loader reads versioned CSV directly. Legacy PostgreSQL seed/cache remain on original CSV."
}
destination.with_suffix(".provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, indent=2))
