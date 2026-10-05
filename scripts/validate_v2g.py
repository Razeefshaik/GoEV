"""Independent nodal KCL power flow with SciPy nonlinear root, not another current sweep.
Run after gradlew v2gBenchmark. Writes numerical validation evidence and fails on mismatch.
"""
import json
from pathlib import Path
import numpy as np
from scipy.optimize import root

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "output/v2g"


def solve(scenario, dispatch):
    cfg = scenario["settings"]
    buses = sorted({1} | {b["from"] for b in scenario["branches"]} | {b["to"] for b in scenario["branches"]})
    positions = {bus: i for i, bus in enumerate(buses)}
    size = len(buses)
    admittance = np.zeros((size, size), complex)
    net = np.zeros(size, complex)
    for branch in scenario["branches"]:
        i, j = positions[branch["from"]], positions[branch["to"]]
        conductance = 1 / complex(branch["rOhm"], branch["xOhm"])
        admittance[i, i] += conductance
        admittance[j, j] += conductance
        admittance[i, j] -= conductance
        admittance[j, i] -= conductance
    for bus in dispatch["flow"]["buses"]:
        net[positions[bus["bus"]]] = complex(bus["servedKw"] - bus["injectionKw"], bus["reactiveKvar"]) * 1000 / 3
    nominal = cfg["voltageKv"] * 1000 / np.sqrt(3)

    def unpack(x):
        return np.r_[complex(nominal), nominal * (x[:size-1] + 1j*x[size-1:])]

    def mismatch(x):
        voltage = unpack(x)
        currents = (admittance @ voltage + np.conj(net / voltage))[1:]
        return np.r_[currents.real, currents.imag]

    result = root(mismatch, np.r_[np.ones(size-1), np.zeros(size-1)], method="hybr", options={"xtol": 1e-10})
    if not result.success or np.max(np.abs(mismatch(result.x))) > 1e-7:
        raise AssertionError(f"Independent solver failed: {result.message}")
    voltages = unpack(result.x)
    max_voltage_error = max(abs(abs(voltages[positions[b["bus"]]]) / nominal - b["voltagePu"])
                            for b in dispatch["flow"]["buses"])
    max_power_error = 0
    losses = 0
    for branch in scenario["branches"]:
        i, j = positions[branch["from"]], positions[branch["to"]]
        current = (voltages[i] - voltages[j]) / complex(branch["rOhm"], branch["xOhm"])
        power = 3 * voltages[i] * np.conj(current) / 1000
        losses += 3 * abs(current)**2 * branch["rOhm"] / 1000
        expected = next(b for b in dispatch["flow"]["lines"] if b["id"] == branch["id"])
        max_power_error = max(max_power_error, abs(power.real - expected["pKw"]), abs(power.imag - expected["qKvar"]))
    assert max_voltage_error < 1e-7, max_voltage_error
    assert max_power_error < 1e-4, max_power_error
    assert abs(losses - dispatch["flow"]["lossesKw"]) < 1e-5
    return {"maxVoltageErrorPu": max_voltage_error, "maxBranchPowerErrorKwKvar": max_power_error,
            "lossErrorKw": abs(losses - dispatch["flow"]["lossesKw"]), "kclResidualA": float(np.max(np.abs(mismatch(result.x))))}


results = []
for name in ["moderate-shortage-none", "moderate-shortage-coordinated", "weak-voltage-coordinated", "excess-injection-coordinated"]:
    run = json.loads((OUTPUT / f"{name}.json").read_text())
    samples = run["samples"]
    sample = max(samples, key=lambda s: s["evKw"])
    results.append({"case": name, **solve(run["scenario"], sample["v2g"])})
    for session in run["sessions"]:
        assert abs(session["deliveredKwh"] - run["scenario"]["settings"]["efficiency"]*session["batteryUsedKwh"]) < 1e-8
        assert session["currentSoc"] >= session["reserveSoc"] - 1e-8
    for sample in samples:
        for policy in ["baseline", "v2g"]:
            dispatch = sample[policy]
            flow = dispatch["flow"]
            assert flow["converged"] and flow["voltageViolations"] == flow["loadingViolations"] == 0
            assert flow["sourceKw"] <= run["scenario"]["settings"]["sourceCapKw"] + 1e-5
            assert abs(flow["balanceResidualKw"]) < .001
        assert sample["v2g"]["servedKw"] + 1e-6 >= sample["baseline"]["servedKw"]
(OUTPUT / "independent-validation.json").write_text(json.dumps({"reference": "SciPy HYBR nodal KCL equations",
    "limitations": "Independent numerical algorithm; same assumed physical model, no hardware validation", "cases": results}, indent=2))
print(json.dumps(results, indent=2))
