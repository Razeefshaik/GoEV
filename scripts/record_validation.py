"""Record actual checks and compare equivalent benchmark inputs; refuses incomplete smoke evidence."""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import numpy
import scipy
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output/v2g"
full = json.loads((OUT / "full-suite-validation.json").read_text())
suites = [ET.parse(p).getroot() for p in (ROOT / "build/test-results/test").glob("TEST-*.xml")]
targeted = {key: sum(int(s.get(key, 0)) for s in suites) for key in ["tests", "failures", "errors", "skipped"]}
assert full["totals"]["failures"] == full["totals"]["errors"] == 0
assert targeted["failures"] == targeted["errors"] == 0
client = subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "dev-clients", "-p", "test_v2g_station.py"],
                        cwd=ROOT, text=True, capture_output=True)
assert client.returncode == 0, client.stderr
smoke = json.loads((OUT / "smoke-validation.json").read_text())
assert all(smoke.get(key, {}).get("passed") for key in ["api", "mqtt", "browser"]), smoke
independent = json.loads((OUT / "independent-validation.json").read_text())
assert len(independent["cases"]) == 4
summary = json.loads((OUT / "summary.json").read_text())
assert len(summary["results"]) == 39
ids = sorted({r["scenario"] for r in summary["results"]})
for scenario_id in ids:
    runs = [json.loads((OUT / f"{scenario_id}-{policy}.json").read_text()) for policy in ["none", "nearest", "coordinated"]]
    assert all(run["scenario"] == runs[0]["scenario"] for run in runs), "unequal benchmark inputs"
    assert all(abs(run["hour"]-run["scenario"]["settings"]["horizonHours"]) < 1e-9 for run in runs)
    for run in runs:
        for sample in run["samples"]:
            for mode in ["baseline", "v2g"]:
                flow = sample[mode]["flow"]
                assert flow["converged"] and flow["voltageViolations"] == flow["loadingViolations"] == 0
                assert abs(flow["balanceResidualKw"]) < .001
report = {"timestampUtc": datetime.now(timezone.utc).isoformat(), "fullGradleSuite": full["totals"],
          "finalTargetedGradleSuite": targeted, "pythonDeviceStateTests": {"passed": True, "count": 2},
          "smoke": smoke, "independentNumericalCases": independent["cases"],
          "benchmarkCases": 39, "identicalInputsAndHorizonsVerified": True,
          "software": {"java": summary["javaVersion"], "python": sys.version.split()[0],
                       "numpy": numpy.__version__, "scipy": scipy.__version__, "gradle": "8.14.1",
                       "springBoot": "3.3.4", "timefold": "2.5.0"},
          "hardwareValidation": "not performed; software simulation only", "islandedOperation": "unsupported",
          "testsNote": "Full suite followed by targeted rerun covering final changes; overlapping tests are not added as unique tests"}
(OUT / "validation-status.json").write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
