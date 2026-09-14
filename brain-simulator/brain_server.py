"""Published Shiu/Brian2 dynamics + measured FlyWire edges; sensor/motor adapters only.

No generated connectivity, invented neuronal update rule, behavioral state machine,
direct stimulus-to-movement path, or fallback brain.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

import brian2 as b2
import numpy as np

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
import importlib.util
vendor_path = ROOT / "vendor" / "shiu" / "model.py"
vendor_source = vendor_path.read_bytes()
if hashlib.sha256(vendor_source).hexdigest() != "fc45837d7122c6ce2a7f3f2f23c515992e4b232aadb919efabb72337fac88e4e":
    raise ValueError("Pinned published model integrity failure")
shiu = importlib.util.module_from_spec(importlib.util.spec_from_file_location("shiu_model", vendor_path))
# Execute the verified bytes, not a possibly stale/unverified bytecode cache.
exec(compile(vendor_source, str(vendor_path), "exec"), shiu.__dict__)

MODEL_ID = "shiu-brian2-fafb783"
b2.prefs.codegen.target = "cython"  # Tested on this Windows/MSVC host; compilation must succeed, no silent backend fallback.


class Circuit:
    def __init__(self, data=DATA):
        self.manifest = json.loads((data / "manifest.json").read_text(encoding="utf-8"))
        for name, expected in self.manifest["files"].items():
            if hashlib.sha256((data / name).read_bytes()).hexdigest() != expected:
                raise ValueError(f"Circuit integrity failure: {name}")
        if self.manifest["materialization"] != 783 or self.manifest["model_commit"] != "91bdd1e7dcf193f3e7ca5a8933497fcef63b7960":
            raise ValueError("Unexpected data/model version")
        with (data / "neurons.csv").open(encoding="utf-8", newline="") as stream:
            self.neurons = list(csv.DictReader(stream))
        params = dict(shiu.default_params)
        # Upstream reset refers to undefined 'w' (a synapse variable, not a neuron variable).
        # Removing this invalid assignment leaves the published v/g reset and all dynamics intact.
        params["eq_rst"] = "v = v_rst; g = 0 * mV"
        self.neu, self.syn, unused_monitor = shiu.create_model(data / "neurons.csv", data / "circuit.parquet", params)
        self.monitor = b2.SpikeMonitor(self.neu, record=False)  # Counts only: no unbounded spike-history storage.
        del unused_monitor
        self.pursuit_types = {"LC10a", "LC9", "LC31a", "LC31b", "LC31c"}
        self.visual = np.array([i for i, row in enumerate(self.neurons) if row["cell_type"] in {"LC4", "LPLC2"} | self.pursuit_types])
        self.stimulus = b2.PoissonGroup(len(self.visual), rates=0 * b2.Hz)
        self.injection = b2.Synapses(self.stimulus, self.neu, on_pre="v_post += sensory_weight",
                namespace={"sensory_weight": params["w_syn"] * params["f_poi"]})
        self.injection.connect(i=np.arange(len(self.visual)), j=self.visual)
        self.neu.rfc[self.visual] = 0 * b2.ms  # Same external-stimulation convention as upstream model.poi.
        self.net = b2.Network(self.neu, self.syn, self.monitor, self.stimulus, self.injection)
        self.net.run(0 * b2.ms)
        self.net.store("rest")

    def reset(self):
        self.net.restore("rest")

    def step(self, senses, dt):
        start = time.perf_counter_ns()
        rates = []
        for i in self.visual:
            side = self.neurons[i]["side"]
            channel = "vision" if self.neurons[i]["cell_type"] in self.pursuit_types else "obstacle"
            left, right = senses[channel + "Left"], senses[channel + "Right"]
            drive = left if side == "left" else right if side == "right" else (left + right) / 2
            rates.append(drive * 150)
        self.stimulus.rates = np.array(rates) * b2.Hz
        before = np.array(self.monitor.count[:], dtype=np.int64)
        self.net.run(dt * b2.second, namespace={})
        hz = (np.array(self.monitor.count[:]) - before) / dt

        def population(types, side=None):
            indices = [i for i, row in enumerate(self.neurons) if row["cell_type"] in types and (side is None or row["side"] == side)]
            return float(np.mean(hz[indices])) if indices else 0.0

        forward = population({"DNp09"})
        backward = population({"MDN"})
        # DNa02 bilateral difference is the measured steering readout; DNa01 can oppose it.
        # DNa01 stays in the circuit, but is not averaged into an incompatible actuator channel.
        left = population({"DNa02"}, "left")
        right = population({"DNa02"}, "right")
        escape = population({"DNp01"})
        # Engineering actuator calibration, not a claim of a reconstructed fly body or behavior.
        # Only recorded descending-neuron firing enters the motor mapping.
        return {"sequence": senses["sequence"], "forward": max(-1.0, min(1.0, (forward - backward) / 100)),
                "turn": max(-1.0, min(1.0, (right - left) / 100)), "ascend": min(1.0, escape / 100),
                "forwardHz": forward, "backwardHz": backward, "leftHz": left, "rightHz": right, "escapeHz": escape,
                "neurons": len(self.neurons), "connections": len(self.syn), "computeNanos": time.perf_counter_ns() - start}


def validate_request(request):
    if not isinstance(request, dict) or request.get("protocol") != 1:
        raise ValueError("Expected protocol 1")
    dt = request.get("dtSeconds")
    if isinstance(dt, bool) or not isinstance(dt, (int, float)) or not math.isfinite(dt) or not .05 <= dt <= 1:
        raise ValueError("dtSeconds must be finite and within [0.05,1]")
    samples = request.get("samples")
    if not isinstance(samples, dict) or len(samples) != 1:
        raise ValueError("Prototype supports exactly one entity per batch")
    for entity, sample in samples.items():
        uuid.UUID(entity)
        if not isinstance(sample, dict): raise ValueError("Invalid sensory frame")
        seq = sample.get("sequence")
        if type(seq) is not int or not 0 <= seq <= 2**63 - 1: raise ValueError("Invalid sequence")
        if type(sample.get("grounded")) is not bool: raise ValueError("grounded must be boolean")
        for field in ["visionLeft", "visionRight", "obstacleLeft", "obstacleRight", "brightness", "damage"]:
            value = sample.get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
                raise ValueError(f"Invalid sensory value: {field}")
    return dt, samples


class BrainHTTP(HTTPServer):
    def __init__(self, port):
        self.circuit = Circuit()
        self.entity = None
        self.last_seen = 0
        super().__init__(("127.0.0.1", port), Handler)

    def step(self, request):
        dt, samples = validate_request(request)
        now = time.monotonic()
        entity, senses = next(iter(samples.items()))
        # One supported creature: reuse compiled model, but never inherit another creature's state.
        if entity != self.entity or now - self.last_seen > 5:
            self.circuit.reset()
        self.entity = entity
        output = self.circuit.step(senses, dt)
        self.last_seen = time.monotonic()
        return {"protocol": 1, "model": MODEL_ID, "outputs": {entity: output}}


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def do_GET(self):
        if self.path != "/health" or self.headers.get("Origin"):
            self.send_error(403)
            return
        if getattr(self.server, "circuit_failed", False):
            self.send_error(503, "Circuit failed; restart required")
            return
        payload = json.dumps({"protocol": 1, "model": MODEL_ID, "ready": True}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def do_POST(self):
        if self.path != "/step" or self.headers.get("Origin") or self.headers.get("Content-Type") != "application/json":
            self.send_error(403); return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 16384: raise ValueError("Invalid body length")
            raw = self.rfile.read(length)
            if len(raw) != length: raise ValueError("Truncated body")
            request = json.loads(raw)
            response = self.server.step(request)
        except (ValueError, TypeError, KeyError) as error:
            self.send_error(400, str(error)); return
        except Exception:
            import traceback
            traceback.print_exc()
            self.server.circuit_failed = True
            self.send_error(503, "Published model failed; motors must stop"); return
        payload = json.dumps(response, allow_nan=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try: self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass

    def log_message(self, fmt, *args):
        if args and str(args[1]) == "200": return
        super().log_message(fmt, *args)


def self_check():
    b2.seed(42)
    circuit = Circuit()
    sample = {"sequence": 1, "visionLeft": 0, "visionRight": 0, "obstacleLeft": 0, "obstacleRight": 0,
              "brightness": .5, "damage": 0, "grounded": True}
    baseline = circuit.step(sample, .25)
    assert baseline["forward"] == baseline["turn"] == baseline["ascend"] == 0, baseline
    stimulus = dict(sample, visionLeft=1, visionRight=1)
    outputs = [circuit.step(dict(stimulus, sequence=i + 2), .25) for i in range(10)]
    assert not circuit.monitor.record and "i" not in circuit.monitor.variables, "Spike history retained"
    assert any(out["forwardHz"] > 0 for out in outputs), "Sensory-to-descending propagation absent"
    circuit.reset()
    circuit.syn.w[:] = 0 * b2.mV  # Causal disconnection control, not a runtime fallback.
    disconnected = [circuit.step(stimulus, .25) for _ in range(3)]
    assert all(out["forward"] == out["turn"] == out["ascend"] == 0 for out in disconnected), disconnected
    report = {"model": MODEL_ID, "backend": b2.prefs.codegen.target, "dtSeconds": .25,
              "maxStepMs": max(out["computeNanos"] for out in outputs) / 1e6,
              "neurons": len(circuit.neurons), "connections": len(circuit.syn),
              "baseline": baseline, "stimulated": outputs, "disconnected": disconnected,
              "checks": "PASS: rest produces no motors; sensory drive reaches descending neurons; disconnecting measured edges abolishes motors"}
    (ROOT.parent / "docs/flywire-evidence/circuit-check.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if args.check: self_check()
    else:
        if not 1024 <= args.port <= 65535: parser.error("Port must be 1024..65535")
        server = BrainHTTP(args.port)
        print(f"READY {MODEL_ID} on 127.0.0.1:{args.port}; no synthetic fallback", flush=True)
        try: server.serve_forever()
        except KeyboardInterrupt: pass
        finally: server.server_close()
