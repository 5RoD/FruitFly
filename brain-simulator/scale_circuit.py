"""Cached FlyWire subset builder and isolated, paced recording-headroom assay.

Never writes the active data directory or sends requests to the live brain.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import time

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

import fetch_circuit as source

OUT = source.ROOT.parent / "docs" / "neuron-scaling"


def rank_partners(pre, post, weight, seed, annotated):
    """One-hop measured partners; every added cell connects to the existing seed."""
    ids = np.concatenate((post[np.isin(pre, seed)], pre[np.isin(post, seed)]))
    values = np.concatenate((abs(weight[np.isin(pre, seed)]), abs(weight[np.isin(post, seed)])))
    unique, inverse = np.unique(ids, return_inverse=True)
    scores = np.bincount(inverse, weights=values)
    valid = ~np.isin(unique, seed) & np.isin(unique, annotated) & (scores > 0)
    unique, scores = unique[valid], scores[valid]
    return unique[np.lexsort((unique, -scores))]


def build(sizes):
    remote = source.RangeFile()
    # Fail before opening Parquet if any pinned range is absent: this command is offline.
    for offset, size in remote.expected:
        if not (source.CACHE / f"{source.MODEL_COMMIT}-{offset}-{size}.bin").is_file():
            raise FileNotFoundError(f"Cached source range missing: {offset}, {size}")
    raw = (source.CACHE / f"annotations-{source.ANNOTATION_COMMIT}.tsv").read_bytes()
    assert hashlib.sha256(raw).hexdigest() == "9a4f8b2f843196074431ebd7cd883536afa1be86c8a4ce90970441e8be81d1be"
    annotations = {int(row["root_id"]): row for row in csv.DictReader(io.StringIO(raw.decode()), delimiter="\t")}
    with (source.DATA / "neurons.csv").open(newline="", encoding="utf-8") as stream:
        seed = np.array(sorted(int(row["root_id"]) for row in csv.DictReader(stream)), dtype=np.int64)
    if any(n <= len(seed) or n > 16000 for n in sizes):
        raise ValueError(f"Request sizes above {len(seed)} and at most 16000 for this bounded assay")
    columns = ["Presynaptic_ID", "Postsynaptic_ID", "Excitatory x Connectivity"]
    parquet = pq.ParquetFile(remote)
    candidates = []
    for group in range(parquet.num_row_groups):
        table = parquet.read_row_group(group, columns=columns, use_threads=False)
        pre, post, weight = [table[name].to_numpy() for name in columns]
        mask = np.isin(pre, seed) | np.isin(post, seed)
        candidates.append(tuple(a[mask] for a in (pre, post, weight)))
    ranked = rank_partners(*(np.concatenate([row[i] for row in candidates]) for i in range(3)),
                           seed, np.array(list(annotations), dtype=np.int64))
    if len(seed) + len(ranked) < max(sizes):
        raise ValueError("Not enough directly connected annotated cells; no disconnected padding")
    ids = np.sort(np.concatenate((seed, ranked[:max(sizes) - len(seed)])))
    edges = []
    for group in range(parquet.num_row_groups):
        table = parquet.read_row_group(group, columns=columns, use_threads=False)
        pre, post, weight = [table[name].to_numpy() for name in columns]
        edges.append(table.filter(pa.array(np.isin(pre, ids) & np.isin(post, ids))))
    full = pa.concat_tables(edges)
    for size in sizes:
        selected = np.sort(np.concatenate((seed, ranked[:size - len(seed)])))
        pre, post = (full[name].to_numpy() for name in columns[:2])
        table = full.filter(pa.array(np.isin(pre, selected) & np.isin(post, selected)))
        for name, id_column in zip(["Presynaptic_Index", "Postsynaptic_Index"], columns[:2]):
            table = table.append_column(name, pa.array(np.searchsorted(selected, table[id_column].to_numpy())))
        path = OUT / f"{size}"
        path.mkdir(parents=True, exist_ok=True)
        with (path / "neurons.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=["root_id", "cell_type", "side", "top_nt"])
            writer.writeheader()
            for rid in selected: writer.writerow({key: annotations[int(rid)][key] for key in writer.fieldnames})
        pq.write_table(table, path / "circuit.parquet")
        manifest = json.loads((source.DATA / "manifest.json").read_text(encoding="utf-8"))
        manifest.pop("bridge_limit", None)
        manifest.update(neurons=size, edges=table.num_rows,
                        boundary="Full induced subgraph; all external cells omitted",
                        selection="Preserve active seed; add annotated one-hop partners ranked by total absolute incident measured weight to seed, root ID tie-break. No new sensory channels or motor rules.",
                        seed_neurons=len(seed), seed_manifest_sha256=hashlib.sha256((source.DATA / "manifest.json").read_bytes()).hexdigest(),
                        new_connectivity_bytes_downloaded=remote.fetched,
                        files={name: hashlib.sha256((path / name).read_bytes()).hexdigest() for name in ["neurons.csv", "circuit.parquet"]})
        (path / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        assert np.isin(seed, selected).all() and remote.fetched == 0
        print(f"BUILT {size} neurons / {table.num_rows} measured edges / downloaded=0 / {path}", flush=True)


def bench(data, output):
    from brain_server import Circuit, b2
    b2.seed(42)
    start = time.perf_counter()
    circuit = Circuit(data)
    setup = time.perf_counter() - start
    sample = dict(sequence=0, visionLeft=0, visionRight=0, obstacleLeft=0, obstacleRight=0,
                  brightness=.5, damage=0, grounded=True)
    cases = {"rest": {}, "both": dict(visionLeft=1, visionRight=1),
             "left": dict(visionLeft=1), "right": dict(visionRight=1),
             "obstacle": dict(obstacleLeft=1, obstacleRight=1),
             "all": dict(visionLeft=1, visionRight=1, obstacleLeft=1, obstacleRight=1)}
    results = {}
    for name, fields in cases.items():
        circuit.reset()
        frame = dict(sample, **fields)
        for _ in range(2): circuit.step(frame, .25)
        rows = []
        for sequence in range(12):
            started = time.perf_counter()
            rows.append(circuit.step(dict(frame, sequence=sequence), .25))
            time.sleep(max(0, .25 - (time.perf_counter() - started)))
        results[name] = rows
        print(f"{name}: max={max(row['computeNanos'] for row in rows)/1e6:.1f}ms", flush=True)
    circuit.reset()
    circuit.syn.w[:] = 0 * b2.mV
    disconnected = [circuit.step(dict(sample, **cases["all"]), .25) for _ in range(3)]
    zero = lambda rows: all(row[key] == 0 for row in rows for key in ["forward", "turn", "ascend"])
    checks = dict(rest_zero=zero(results["rest"]), disconnected_zero=zero(disconnected),
                  forward_response=any(row["forwardHz"] > 0 for row in results["both"]),
                  left_turn=float(np.mean([row["turn"] for row in results["left"]])) < 0,
                  right_turn=float(np.mean([row["turn"] for row in results["right"]])) > 0,
                  obstacle_response=any(row["escapeHz"] > 60 for row in results["obstacle"]))
    times = np.array([row["computeNanos"] / 1e6 for rows in results.values() for row in rows])
    timing = dict(zip(["p50Ms", "p95Ms", "p99Ms", "maxMs"], map(float, np.percentile(times, [50, 95, 99, 100]))))
    report = dict(data=str(data.resolve()), backend=str(b2.prefs.codegen.target), neurons=len(circuit.neurons),
                  edges=len(circuit.syn), setupSeconds=setup, dtSeconds=.25, samples=len(times), seed=42,
                  timing=timing, checks=checks, headroomTargetMs=187.5,
                  candidate=all(checks.values()) and timing["p95Ms"] <= 187.5 and timing["maxMs"] < 250,
                  context="Isolated additional process alongside live game and brain; recording/FPS not verified. Short assay, not a recording-safe maximum.",
                  results=results, disconnected=disconnected)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in {"results", "disconnected"}}, indent=2), flush=True)


def check():
    ranked = rank_partners(np.array([1, 1, 4, 2, 9]), np.array([3, 4, 2, 5, 8]),
                           np.array([2, -4, 1, 5, 100]), np.array([1, 2]), np.array([1, 2, 3, 4, 5, 8, 9]))
    assert ranked.tolist() == [4, 5, 3], ranked  # Signed strength, tie-break, seed exclusion, no unrelated cells.
    print("PASS: measured partner ranking, deterministic ties, no disconnected padding")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--build", type=int, nargs="+")
    group.add_argument("--bench", type=Path)
    group.add_argument("--check", action="store_true")
    parser.add_argument("--output", type=Path, default=OUT / "benchmark.json")
    args = parser.parse_args()
    if args.build: build(sorted(set(args.build)))
    elif args.bench: bench(args.bench, args.output)
    else: check()
