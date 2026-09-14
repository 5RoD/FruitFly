# Release-candidate validation

FruitFly 0.3.0 is a playable experimental prototype, not a finished scientific
reconstruction or a guaranteed horror experience.

## Public packaging checks

The sanitized source export built successfully with Java 21. The setup helper's
22 tests passed, including a real nested process's stdout/stderr and console
`stop` input. A clean client installation verified FruitFly, Fabric API and
optional Simple Voice Chat checksums. A fresh server setup downloaded the pinned
Fabric installer, created a new Python 3.14 environment, installed the locked
requirements and passed the real 918-neuron Brian2/Cython causal check. Its
headless `run-server` then started successfully, accepted a creature summon,
reported healthy managed neural service and stopped cleanly through the console.
These are fresh package checks; they do not replace the multiplayer/voice limits
below. Published source and JARs were checked for known secret patterns, personal
paths, private runtime files and model metadata. No matches were found in that scan.

Before the public export, the full Gradle build and existing asset, neural
transport, controller, recovery, surface, sound and optional voice checks passed.
The test suite includes malformed input, opt-in lifecycle and transfer bounds.
New decision/protocol/lifecycle coverage measured 81.4% lines and 57.3% branches
in the combined local fixture run. Unit checks alone do not establish visual or
multiplayer quality.

Two real Minecraft clients were tested together against a dedicated server:
movement, shared entity state, marker entry/reset, feeding presentation, Creative
immunity, ceiling attachment and brain interruption/recovery. Separate live
server tests covered six-HP feeding, growth clearance/persistence, route obstacles
and limited stone-pickaxe-equivalent block breaking with protection checks.
The ten authored animation clips plus runtime feeding were viewed in labeled
in-game capture from multiple angles, with normal and slowed playback. Prescribed
animation-fixture paths are renderer tests, not evidence of AI navigation.

## Known limits

- Human microphone capture/replay quality, simultaneous human speech and the full
  human-client voice deletion/reconnection workflow remain unverified. Optional
  Simple Voice Chat handshake/API/protocol tests passed; that is not equivalent.
- A 120 FPS cap was used for the two-client measurements. Sampled frame-time p95
  was about 10.6-11.6 ms, so a locked 120 FPS is not promised. OBS/other recording
  software running concurrently was not tested.
- 918/1500/2500/4000/8000-neuron candidates passed directional/causal assays but
  none met the 187.5 ms inference-p95 target under the varying concurrent test
  load. The release keeps 918 neurons. CPU Cython simulation does not use CUDA.
- Only the Windows development machine and the environments explicitly recorded
  in release checks have been tested. More hardware and long-duration sessions
  need validation. Use a copy of valuable worlds when testing a prototype.
- Private game worlds, raw logs, recordings and machine-specific evidence are
  intentionally not published. Runnable test sources are included.

## Repeat the checks

```powershell
py test_manage.py
.\gradlew.bat build
.\gradlew.bat -PpredatorCoverage predatorCoverage
.\.venv-flywire\Scripts\python.exe brain-simulator\brain_server.py --check
.\.venv-flywire\Scripts\python.exe brain-simulator\test_health.py
```

`manage.py setup` creates the brain check's output directory. When running the
check manually first, create `docs/flywire-evidence` beforehand. Gradle's optional
Minecraft fixtures are in `src/predatorTest` and `src/animationTest`; they create
isolated runtime folders and require explicit server EULA setup. They are never
packaged into the mod. Blender authoring tools are optional; they are not needed
to play, build the shipped assets, or run the brain.
