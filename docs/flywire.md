# FlyWire data and simulation provenance

## What this mod actually uses

**918 real neurons and 32,057 measured signed edges**, FAFB materialization 783.
Structural connectivity is not a working brain. The numerical dynamics are the
published Shiu/Brian2 model, not an invented neuron update rule. Minecraft sensor
adapters stimulate selected visual populations. The hybrid game controller owns
pathfinding, encounters and combat; measured neural output modulates urgency and
reaction. No claim is made that the connectome learned Minecraft or naturally
generates every horror behavior.

The shipped circuit is about **179 KB** including its manifest. Setup uses it as
is and checks file hashes. It never downloads the full connectome automatically.

## Sources, formats, versions and access

| Resource | Use/access |
|---|---|
| [FlyWire project](https://flywire.ai/) / [data entry point](https://flywire.ai/data) | Official landing pages. The data page can redirect or be unavailable; use the explicit archive below rather than guessing replacement links. |
| [FlyWire Codex](https://codex.flywire.ai/) | Interactive exploration and exports. Authenticated Codex downloads are not required or verified by this release. Do not put account tokens in source. |
| [FlyConnectome](https://github.com/flyconnectome) | Official annotation and analysis repositories. Pin commits when reproducing a circuit. |
| [Canonical connectivity archive](https://zenodo.org/records/10676866) | Version **783.0**, CC BY 4.0. Public file inventory available through its [record API](https://zenodo.org/api/records/10676866). |
| [Shiu model](https://github.com/philshiu/Drosophila_brain_model/tree/91bdd1e7dcf193f3e7ca5a8933497fcef63b7960) | Commit `91bdd1e7dcf193f3e7ca5a8933497fcef63b7960`; Python source MIT. [Nature paper](https://www.nature.com/articles/s41586-024-07763-9). |
| [Neuron annotations](https://github.com/flyconnectome/flywire_annotations/tree/8587524c1748ce5ef2080822a2fc890fc03bf597) | Commit `8587524c1748ce5ef2080822a2fc890fc03bf597`; TSV. No separate license declared at this pin; see [notices](../THIRD_PARTY_NOTICES.md), not a blanket MIT/CC claim. |

Canonical downloadable file inventory (byte sizes, not RAM requirements):

| Exact filename and procedure | Bytes | Format |
|---|---:|---|
| [proofread_root_ids_783.npy](https://zenodo.org/api/records/10676866/files/proofread_root_ids_783.npy/content) | 1,114,168 | NumPy neuron IDs |
| [proofread_connections_783.feather](https://zenodo.org/api/records/10676866/files/proofread_connections_783.feather/content) | 852,022,274 | Arrow aggregated connections |
| [flywire_synapses_783.feather](https://zenodo.org/api/records/10676866/files/flywire_synapses_783.feather/content) | 9,492,998,242 | Arrow individual filtered synapses |

Full connectivity is downloadable locally. Read the record API/checksums first;
stream only specifically needed files and process selected Arrow columns/batches,
not an all-synapse pandas conversion. The canonical aggregate stores pre/post root
IDs, neuropil, synapse counts and transmitter probabilities. Synapse-level tables
also include confidence values and pre/post positions in nanometers. Preserve
64-bit root IDs, not JavaScript floating-point IDs. Pair/neuropil aggregation and
synapse confidence filters change reported counts.

Morphology is separate: [783 skeleton/NBLAST archive](https://zenodo.org/records/10877326),
CC BY 4.0, includes a roughly 5.36 GB skeleton Parquet. It is not needed for the
point-neuron simulation or the supplied creature mesh and is not downloaded.

## Exact extraction

The author's [Connectivity_783.parquet](https://raw.githubusercontent.com/philshiu/Drosophila_brain_model/91bdd1e7dcf193f3e7ca5a8933497fcef63b7960/Connectivity_783.parquet)
is **100,804,642 bytes**. Only three columns are fetched with checked HTTP ranges:
`Presynaptic_ID`, `Postsynaptic_ID`, `Excitatory x Connectivity`. The last is an
author-derived signed count, not a directly measured electrical conductance.

Annotations are the pinned
[Supplemental_file1_neuron_annotations.tsv](https://raw.githubusercontent.com/flyconnectome/flywire_annotations/8587524c1748ce5ef2080822a2fc890fc03bf597/supplemental_files/Supplemental_file1_neuron_annotations.tsv)
(31,718,505 bytes, SHA256
`9a4f8b2f843196074431ebd7cd883536afa1be86c8a4ce90970441e8be81d1be`).

`brain-simulator/source-ranges.json` pins every accepted offset/length/hash.
`fetch_circuit.py` rejects non-206 responses and oversized/truncated/range-mismatched
payloads; it has a 65 MB connectivity-transfer cap and no full-download fallback.
With the pinned requirements installed, explicit reconstruction is:

```powershell
.\.venv-flywire\Scripts\python.exe brain-simulator\fetch_circuit.py
```

This is optional, replaces the selected data with a reproducible extraction, and
can require roughly 100 MB of source-cache space including annotations. Never run
it during filming. The shipped output uses CSV (`root_id,cell_type,side,top_nt`)
plus Parquet (IDs, local indices, signed weights) and JSON hashes/provenance.
The pinned vendor model and MIT notice are downloaded/verified by the same script.

Selected sensory populations: LC4/LPLC2 (looming/escape-related) and LC10a/LC9/LC31
(visual object-related). AOTU019/AOTU025 and the strongest 128 qualifying bridge
partners connect to descending populations DNp01, DNp09, DNa01, DNa02 and MDN.
These are higher-order visual neurons, not photoreceptors. Descending neurons are
motor-related outputs, not muscles or the complete peripheral motor system.
The extractor keeps edges touching the selected core, not bridge-to-bridge edges
or all surrounding neurons. Circuit truncation is explicit, not filled by fake edges.

## Model assumptions and packages

[Brian2](https://brian2.readthedocs.io/en/stable/) 2.10.1 executes the pinned
leaky-integrate-and-fire equations on the CPU using Cython. Its BSD licensing and
bundled notices remain in the installed package. `requirements.txt` locks the
complete tested Python package versions. No GPU/CUDA requirement is introduced.

The model supplies assumed membrane/reset/threshold values, time constants,
refractory time, delays and a global synaptic scaling. Structural counts alone
do not supply these, detailed receptor physiology, plasticity, VNC/body dynamics,
sensory transduction or all neuromodulation. The adapter changes the invalid
upstream neuron reset reference to `v = v_rst; g = 0 * mV`, retaining the valid
published reset behavior. Vendor source bytes remain hash-pinned and unmodified.

Other usable tools include [fafbseg-py](https://github.com/navis-org/fafbseg-py)
(FlyWire queries/analysis, GPL-3.0), [CAVEclient](https://github.com/CAVEconnectome/CAVEclient)
(authenticated materialized tables, MIT), and
[connectome_interpreter](https://github.com/YijieYin/connectome_interpreter)
(path/rate-model analysis, MIT). They are not required, installed or authenticated
by this mod. [Eon fly-brain](https://github.com/eonsystemspbc/fly-brain) provides a
derived computational implementation (GPL-2.0), not the canonical dataset; it is
an uninstalled optimization candidate, not a validated interchangeable backend.

## Hardware and scaling

Use the functionally selected subset first. The tested Windows host had an
i9-10900F, 32 GB RAM and RTX 3050 8 GB. Cython compilation needs an installed C++
toolchain. Leave RAM for Minecraft and recording; compressed dataset size does
not equal simulation RAM. Full-brain RAM/runtime depends on model/state/synapses
and backend; no full-connectome hardware benchmark was performed here.

918/1500/2500/4000/8000 candidate circuits passed rest, stimulus, direction and
disconnected-edge controls, but missed the **187.5 ms p95 at 4 Hz** target under
varying concurrent Minecraft load. Keep 918 by default. Do not treat those trials
as a controlled ranking or claim a larger circuit is smarter. Require causal,
directional and closed-loop gameplay checks plus simultaneous recording-load
profiling before expanding. [Validation limits](validation.md) remain explicit.
