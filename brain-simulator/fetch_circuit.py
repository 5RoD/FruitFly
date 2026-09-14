"""Extract a bounded, provenance-pinned circuit; never fetch the whole synapse archive.

Only three columns of the author's 100 MB Parquet are fetched via HTTP ranges.
All connectivity and its sign come from the published data, never random edges.
"""
import csv
import hashlib
import io
import json
from pathlib import Path
import urllib.request

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
CACHE = DATA / "source-cache"
MODEL_COMMIT = "91bdd1e7dcf193f3e7ca5a8933497fcef63b7960"
ANNOTATION_COMMIT = "8587524c1748ce5ef2080822a2fc890fc03bf597"
MODEL_BASE = f"https://raw.githubusercontent.com/philshiu/Drosophila_brain_model/{MODEL_COMMIT}/"
ANNOTATIONS = f"https://raw.githubusercontent.com/flyconnectome/flywire_annotations/{ANNOTATION_COMMIT}/supplemental_files/Supplemental_file1_neuron_annotations.tsv"
CONNECTIVITY = MODEL_BASE + "Connectivity_783.parquet"
SIZE = 100804642
PURSUIT_TYPES = {"LC10a", "LC9", "LC31a", "LC31b", "LC31c"}
CORE_TYPES = {"LC4", "LPLC2", "DNp01", "DNp09", "DNa01", "DNa02", "MDN", "AOTU019", "AOTU025"} | PURSUIT_TYPES
VENDOR_HASHES = {"model.py": "fc45837d7122c6ce2a7f3f2f23c515992e4b232aadb919efabb72337fac88e4e",
                 "LICENSE": "3621f6d6476189190e2960fa43f11b275ba4eca848fdac50a1ba2925de6223e8"}


def download(url, path, limit, expected):
    if path.exists():
        data = path.read_bytes()
    else:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(limit + 1)
    if len(data) > limit or hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Pinned source integrity failure: {url}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return data


class RangeFile(io.RawIOBase):
    """Seekable HTTP range reader with a hard 65 MB transfer cap and persistent cache."""
    def __init__(self):
        self.pos = 0
        self.fetched = 0
        self.ranges = []
        lock = (ROOT / "source-ranges.json").read_bytes()
        if hashlib.sha256(lock).hexdigest() != "516e1c8ed3d8f747d625864a34879a81dc324ab10dcdc4e581ecb00452c18c20":
            raise ValueError("Source range lock was modified")
        self.expected = {(row["offset"], row["size"]): row["sha256"] for row in json.loads(lock)}

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset if whence == 0 else self.pos + offset if whence == 1 else SIZE + offset
        if not 0 <= self.pos <= SIZE:
            raise ValueError("Seek outside verified source")
        return self.pos

    def read(self, size=-1):
        size = SIZE - self.pos if size < 0 else min(size, SIZE - self.pos)
        if not size: return b""
        start = self.pos
        expected_hash = self.expected[(start, size)]
        path = CACHE / f"{MODEL_COMMIT}-{start}-{size}.bin"
        if path.exists():
            data = path.read_bytes()
        else:
            if self.fetched + size > 65_000_000:
                raise ValueError("Range-download budget exceeded; no full-download fallback")
            request = urllib.request.Request(CONNECTIVITY, headers={"Range": f"bytes={start}-{start + size - 1}"})
            with urllib.request.urlopen(request, timeout=60) as response:
                expected = f"bytes {start}-{start + size - 1}/{SIZE}"
                if response.status != 206 or response.headers.get("Content-Range") != expected:
                    raise ValueError("Source did not honor exact range; refusing full download")
                data = response.read(size + 1)
            if len(data) != size: raise ValueError("Truncated or oversized range")
            self.fetched += size
        if len(data) != size or hashlib.sha256(data).hexdigest() != expected_hash:
            raise ValueError("Pinned connectivity range integrity failure")
        path.write_bytes(data)
        self.ranges.append({"offset": start, "size": size, "sha256": hashlib.sha256(data).hexdigest()})
        self.pos += size
        return data


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    metadata = download(ANNOTATIONS, CACHE / f"annotations-{ANNOTATION_COMMIT}.tsv", 32_000_000,
                        "9a4f8b2f843196074431ebd7cd883536afa1be86c8a4ce90970441e8be81d1be")
    annotations = {int(row["root_id"]): row for row in csv.DictReader(io.StringIO(metadata.decode()), delimiter="\t")}
    core = {rid for rid, row in annotations.items() if row["cell_type"] in CORE_TYPES}
    counts = {name: sum(annotations[rid]["cell_type"] == name for rid in core) for name in sorted(CORE_TYPES)}
    if any(counts[name] == 0 for name in CORE_TYPES): raise ValueError(f"Missing core cell type: {counts}")
    print("Selected core:", counts, flush=True)
    remote = RangeFile()
    parquet = pq.ParquetFile(remote)
    columns = ["Presynaptic_ID", "Postsynaptic_ID", "Excitatory x Connectivity"]
    candidates = []
    core_array = np.array(sorted(core), dtype=np.int64)
    for group in range(parquet.num_row_groups):
        table = parquet.read_row_group(group, columns=columns, use_threads=False)
        pre, post, weight = [table[name].to_numpy() for name in columns]
        mask = np.isin(pre, core_array) | np.isin(post, core_array)
        candidates.extend(zip(pre[mask].tolist(), post[mask].tolist(), weight[mask].tolist()))
        print(f"Scanned column ranges {group + 1}/{parquet.num_row_groups}; transferred={remote.fetched}", flush=True)
    # Include all core cells, plus strongest partners that bridge visual core to descending core.
    visual = {rid for rid in core if annotations[rid]["cell_type"] in {"LC4", "LPLC2"} | PURSUIT_TYPES}
    outputs = {rid for rid in core if annotations[rid]["cell_type"] in {"DNp01", "DNp09", "DNa01", "DNa02", "MDN"}}
    visual_to = {}; to_output = {}
    for pre, post, weight in candidates:
        if pre in visual: visual_to[post] = visual_to.get(post, 0) + abs(weight)
        if post in outputs: to_output[pre] = to_output.get(pre, 0) + abs(weight)
    bridges = set(visual_to) & set(to_output) & set(annotations)
    ranked = sorted(bridges - core, key=lambda rid: (-min(visual_to[rid], to_output[rid]), rid))
    selected = core | set(ranked[:128])
    ids = sorted(selected)
    index = {rid: i for i, rid in enumerate(ids)}
    # Candidates contain every edge touching core, but not bridge->bridge edges. Explicitly an extracted pathway,
    # not a complete induced subgraph; the manifest records this boundary rather than inventing recurrence.
    edges = [(a, b, w) for a, b, w in candidates if a in selected and b in selected]
    if not edges: raise ValueError("Selected circuit has no measured edges")
    with (DATA / "neurons.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["root_id", "cell_type", "side", "top_nt"])
        writer.writeheader()
        for rid in ids: writer.writerow({key: annotations[rid][key] for key in writer.fieldnames})
    pq.write_table(pa.table({
        "Presynaptic_ID": [a for a, b, w in edges], "Postsynaptic_ID": [b for a, b, w in edges],
        "Presynaptic_Index": [index[a] for a, b, w in edges], "Postsynaptic_Index": [index[b] for a, b, w in edges],
        "Excitatory x Connectivity": [w for a, b, w in edges],
    }), DATA / "circuit.parquet")
    vendor = ROOT / "vendor" / "shiu"
    for name in ["model.py", "LICENSE"]:
        download(MODEL_BASE + name, vendor / name, 20_000, VENDOR_HASHES[name])
    manifest = {
        "dataset": "FlyWire FAFB", "materialization": 783,
        "connectivity_url": CONNECTIVITY, "connectivity_total_bytes": SIZE,
        "connectivity_columns": columns, "connectivity_rows": parquet.metadata.num_rows,
        "model_commit": MODEL_COMMIT, "annotation_commit": ANNOTATION_COMMIT,
        "annotations_url": ANNOTATIONS, "annotation_bytes": len(metadata),
        "annotation_sha256": hashlib.sha256(metadata).hexdigest(),
        "core_types": counts, "bridge_limit": 128, "neurons": len(ids), "edges": len(edges),
        "boundary": "All edges touching core within selected cells; bridge-to-bridge edges and all external cells omitted",
        "new_connectivity_bytes_downloaded": remote.fetched, "ranges": remote.ranges,
        "files": {name: hashlib.sha256((DATA / name).read_bytes()).hexdigest() for name in ["neurons.csv", "circuit.parquet"]},
        "code_license": "MIT (Shiu and Spiller)",
        "data_license_note": "Canonical FlyWire archive CC-BY-4.0; author-derived table and annotation provenance retained; see docs/flywire.md",
    }
    (DATA / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"EXTRACTED {len(ids)} real neurons, {len(edges)} measured signed edges; no generated connections", flush=True)


if __name__ == "__main__": main()
