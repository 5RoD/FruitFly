"""Real-data object-tracking/escape dose and laterality assay."""
import json
import math
from pathlib import Path
import brian2 as b2
from brain_server import Circuit, ROOT


def main():
    circuit = Circuit()
    rest = dict(sequence=0, visionLeft=0, visionRight=0, obstacleLeft=0, obstacleRight=0,
                brightness=1, damage=0, grounded=True)
    report = {}
    for name, changes in {
        "rest": {}, "object_both": {"visionLeft": .375, "visionRight": .375},
        "object_left": {"visionLeft": .75}, "object_right": {"visionRight": .75},
        "obstacle": {"obstacleLeft": 1, "obstacleRight": 1},
    }.items():
        circuit.reset()
        b2.seed(42)
        report[name] = [circuit.step(dict(rest, **changes, sequence=i), .25) for i in range(8)]
    circuit.reset()
    circuit.syn.w[:] = 0 * b2.mV
    report["disconnected"] = [circuit.step(dict(rest, visionLeft=.75, visionRight=.75, sequence=i), .25) for i in range(4)]
    for name in ("rest", "disconnected"):
        assert all(out["forward"] == out["turn"] == out["ascend"] == 0 for out in report[name]), name
    assert sum(row["forwardHz"] for row in report["object_both"]) > 0
    assert sum(row["turn"] for row in report["object_left"]) < 0
    assert sum(row["turn"] for row in report["object_right"]) > 0
    assert sum(row["escapeHz"] for row in report["obstacle"]) > sum(row["escapeHz"] for row in report["object_both"])
    circuit.reset()  # Restores measured synaptic weights after disconnection control.
    for seed in (42, 43, 44):
        for degrees in (-30, 0, 30):
            circuit.reset()
            b2.seed(seed)
            bearing = math.sin(math.radians(degrees))
            signal = (1 - 3 / 16) * math.cos(math.radians(degrees))
            sample = dict(rest, visionLeft=signal * (1-bearing)/2, visionRight=signal * (1+bearing)/2)
            values = [circuit.step(dict(sample, sequence=i), .25) for i in range(4)]
            report[f"sensor_bearing_{degrees}_seed_{seed}"] = values
            if degrees: assert sum(row["turn"] for row in values) * degrees > 0, (seed, degrees, values)
    target = ROOT.parent / "docs/flywire-evidence/pursuit-check.json"
    target.write_text(json.dumps(report, indent=2), encoding="utf-8")
    for name, outputs in report.items():
        print(name, {key: round(sum(row[key] for row in outputs) / len(outputs), 3)
                     for key in ("forwardHz", "leftHz", "rightHz", "escapeHz", "forward", "turn")})
    print("REPORT", target)


if __name__ == "__main__": main()
