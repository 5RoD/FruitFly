# FruitFly

A curious, unsettling Minecraft predator with an animated custom creature and
a local **real FlyWire-derived neural circuit**. For **Fabric 1.21.1 / Java 21**.

The fly watches, investigates, tests reactions and retreats. Recording markers
can trigger hunts, airborne feeding, capped blood growth and limited shelter
breaches. Optional Simple Voice Chat integration replays consenting players'
short speech clips from the creature.

**Experimental 0.3.0:** two-client gameplay has been exercised; human voice replay
and recording-software load still need testing. See [verified results and limits](docs/validation.md).

## Quick start: play with a friend

1. Download and unzip **[FruitFly-0.3.0-starter.zip](https://github.com/5RoD/FruitFly/releases/download/v0.3.0/FruitFly-0.3.0-starter.zip)**
   from [Releases](https://github.com/5RoD/FruitFly/releases). It includes the mod,
   setup helper and small neural circuit. No Git, build tools for Java, dataset
   account, API key or full connectome download is needed for the starter package.
2. Install the prerequisites below on the **host**. In the unzipped folder run:

   ```powershell
   py -3.14 manage.py setup --server
   ```

   This downloads the pinned official Fabric server/dependencies, prepares Python,
   checks the real circuit and connects its paths to the new `server` folder.
   It does not launch Minecraft or change your existing worlds.
3. Read the [Minecraft EULA](https://www.minecraft.net/eula). If you agree, set
   `eula=true` in `server/eula.txt`, then run:

   ```powershell
   py -3.14 manage.py run-server
   ```

   The mod manages the local brain automatically. Allow Minecraft TCP 25565 as
   needed for your own server/network. Never forward the local brain's port 8765.
4. Each player uses a **Fabric 1.21.1 profile with Loader 0.19.5**, created using
   the [official Fabric installer](https://fabricmc.net/use/installer/).
   Install the mod in that profile's game directory:

   ```powershell
   py manage.py setup --client --game-dir "D:\Games\MyFabricInstance"
   ```

   Replace the example directory with your launcher's **instance/game folder**,
   not its `mods` subfolder. This installs FruitFly and Fabric API; it does not
   create a launcher profile. The client setup helper needs Python 3.11 or newer.
   Client-only players need no neural packages or C++
   compiler. Alternatively copy the release JAR and matching Fabric API into
   `mods` manually, without Python.
5. Join the server (`localhost` for the host, your server address for your friend).
   In the server console type `op YourMinecraftName`. In game:

   ```text
   /flybrain spawn
   /flybrain brain status
   /flybrain scoreboard behavior
   /flybrain scene give
   ```

   Place the marker, leave its eight-block radius for two seconds, and return.
   Use `/flybrain scene reset` to repeat or `/flybrain scene stop` to cancel.
   **[All commands](docs/commands.md)**

### Host prerequisites (one-time)

- **Java 21 JDK**, available on PATH or through `JAVA_HOME`:
  [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21).
- **CPython 3.14, 64-bit**, with pip/venv:
  [Python downloads](https://www.python.org/downloads/).
- **C++ compiler for Brian2/Cython**. Windows: install
  [Visual Studio Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)
  with **Desktop development with C++**, MSVC and a Windows SDK. Linux needs its
  normal C++ build toolchain and Python development headers. The first neural
  check compiles kernels and can take several minutes. Later starts reuse them.
- Internet for initial dependency downloads and several GB of free disk space
  for Java/Python/Minecraft packages and caches. Budget 4 GB for the server plus
  brain/client memory; 32 GB system RAM is a comfortable tested development setup,
  not a measured minimum.
- Use a normal local filesystem such as NTFS, APFS or ext4. The installer uses
  atomic hard links to avoid overwriting files accidentally; FAT/exFAT drives
  and filesystems without hard-link support are not supported by the helper.

On Linux/macOS use `python3.14` in place of `py -3.14`. Windows is the tested host;
the portable setup script has automated checks, but other OS gameplay is unverified.
CUDA is not required or used. Keep the default 918-neuron circuit when filming.

### Existing server or single-player instance

Close that instance first. Back up valuable worlds, then:

```powershell
py -3.14 manage.py setup --game-dir "D:\Games\MyFabricInstance"
```

This host setup is also what single-player needs. The `--client` option is only
for joining someone else's server. Existing unrelated config fields are preserved;
the helper refuses conflicting mod files rather than silently replacing them.
After closing Minecraft, `--replace` explicitly backs up and replaces conflicting
mod versions. Backups remain local beside the replaced files.
Keep the unzipped FruitFly folder in place: configuration points at its brain
environment. If you move it, rerun setup with the same game directory.

### Optional voice mimicry

Add `--voicechat` to setup on the server **and both clients**. This separately
downloads **Simple Voice Chat Fabric 1.21.1-2.6.22**; it is not bundled with FruitFly.
Voice Chat needs working UDP connectivity, normally **24454**. See the
[official server setup](https://modrepo.de/minecraft/voicechat/wiki/server_setup).

Capture is off by default. Each player must opt in with `/flybrain voice start`,
read its storage/sharing confirmation, then confirm. Receive/share controls,
capacity and deletion are explained in [commands](docs/commands.md). No raw mic
capture by FruitFly, group recording, full-session archives or voice cloning.

## Troubleshooting

| Symptom | Check |
|---|---|
| Fly is frozen | `/flybrain brain status`. Missing/stale neural output deliberately freezes it. Check the configured Python/script paths and compiler, then `/flybrain brain restart`. |
| Compiler/MSVC error | Install the host C++ prerequisites above and rerun setup. There is no silent synthetic brain fallback. |
| Java/Gradle version error | Use a Java 21 JDK; restart the terminal after installing it. |
| Wrong mods/version | Use a separate Fabric **1.21.1** instance, not Forge/NeoForge or another Minecraft version. |
| Marker does nothing | Spawn/watch a nearby fly first; leave the radius with both eligible players, wait two seconds, return. Spent markers need reset. |
| No voice clips | Both sides need SVC and UDP; check PTT/mute, groups, recording consent, sharing and receive permissions. |
| Port 8765 in use | A healthy matching service is reused. Resolve unrelated port conflicts yourself; the supervisor does not kill unrelated processes. |

Stop the server with its normal `stop` console command. Save and close Minecraft
before updating. Keep your worlds/config/recordings private and out of Git.

## Build and contribute

Source ZIP users do not need Git. With Java 21:

```powershell
.\gradlew.bat build
py test_manage.py
```

Unix: `./gradlew build`. Output: `build/libs/flybrain-0.3.0.jar`.
Existing automated checks run as part of `build`; neural runtime checks need the
host setup. See [validation](docs/validation.md) for optional integration fixtures.
Blender authoring is optional. `tools/author_creature_animations.py` generates the
editable `.blend` from the included motion rig before preview/bake tools are used.

Keep gameplay, movement, neural simulation and rendering separate. Brain output
modulates server AI: downloading synapses does **not** produce a self-contained
fly brain, attack policy or learned Minecraft navigation. Read
[FlyWire provenance and model assumptions](docs/flywire.md).

Original code is MIT; model assets have separate project-use terms. Scientific
sources and dependency licenses are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Contact: **5RoD@gravemc.net**. Please report reproducible problems without uploading
credentials, personal recordings, game worlds or unredacted private logs.
