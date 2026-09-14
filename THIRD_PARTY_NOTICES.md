# Attribution and asset terms

## FruitFly

Original mod/bridge code, documentation and generated insect audio: MIT, copyright 2026 5RoD.
Contact: 5RoD@gravemc.net.

The supplied Meshy-generated creature GLBs in `src/main/resources/assets/flybrain/models/`
and `art/creature/` are not covered by the code's MIT license. They are provided for
running, building and redistributing FruitFly as part of this mod. Standalone asset
reuse or redistribution requires the maintainer's permission. This does not assert
rights over third-party material. The supplied mesh appearance is preserved; approved
rig-weight repairs and authored animation tracks are included.

## Published neural model

`brain-simulator/vendor/shiu/model.py` is the unmodified, hash-pinned implementation
from [Philip Shiu and Nico Spiller's Drosophila brain model](https://github.com/philshiu/Drosophila_brain_model/tree/91bdd1e7dcf193f3e7ca5a8933497fcef63b7960).
Its [MIT license](brain-simulator/vendor/shiu/LICENSE) is retained alongside it.
The adapter corrects an upstream reset expression referencing an undefined neuron
variable; the vendor file itself is unchanged. Cite
[Shiu et al., Nature (2024)](https://www.nature.com/articles/s41586-024-07763-9).

## FlyWire structural data and annotations

The [canonical FAFB 783.0 connectivity archive](https://zenodo.org/records/10676866)
is CC BY 4.0. Attribution: the FlyWire Consortium and contributors listed on that
record; see [Dorkenwald et al.](https://doi.org/10.1038/s41586-024-07558-y).
The included tiny circuit is a selected pathway, not the full connectome. It uses
the signed aggregate table from Shiu's pinned repository and intentionally omits
external cells and bridge-to-bridge edges. `brain-simulator/data/manifest.json`
records the exact sources, extraction boundary, columns, commits and hashes.

Neuron type/side/transmitter annotations originate from
[flyconnectome/flywire_annotations](https://github.com/flyconnectome/flywire_annotations/tree/8587524c1748ce5ef2080822a2fc890fc03bf597)
and the cited [Schlegel et al. publication](https://doi.org/10.1038/s41586-024-07686-5),
[Matsliah et al.](https://doi.org/10.1038/s41586-024-07981-1), and
[Berg et al.](https://doi.org/10.1101/2025.10.09.680999).
That pinned repository does not declare a separate code license. The canonical
connectivity archive's license is not a blanket license for every upstream
annotation resource. Only the factual neuron fields required by this subset are
included; no upstream annotation software is copied. Consult upstream terms for
other uses.

## Dependencies downloaded separately

- Brian2: BSD-3-Clause and bundled component notices; installed through pip.
- Fabric Loader, Fabric API and the Fabric installer: respective upstream Apache-2.0 notices.
- Simple Voice Chat: GPL-3.0, optional separate download. API used compile-only;
  neither its API nor runtime is bundled in the FruitFly JAR or starter ZIP.
- Minecraft: Mojang/Microsoft proprietary software, downloaded by the official
  Fabric installer, not distributed here. Each server operator must accept the
  [Minecraft EULA](https://www.minecraft.net/eula).
- Gradle wrapper: Apache-2.0; [upstream license](https://github.com/gradle/gradle/blob/v8.12.1/LICENSE).

Dependency installation retains upstream package licenses. No copied film audio,
voice cloning, personal recordings, game worlds or credentials are included.
