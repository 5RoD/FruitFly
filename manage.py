#!/usr/bin/env python3
"""Small, stdlib-only setup for an explicitly selected Fabric instance. Never edits launcher profiles."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent
MINECRAFT, LOADER, VERSION = "1.21.1", "0.19.5", "0.3.0"
MAX_DOWNLOAD = 64 * 1024 * 1024
COMPILER_HELP = (
    "The real Brian2/Cython check must succeed; there is no substitute brain/backend. "
    "If the output reports a C++ compiler error, install Visual Studio Build Tools with "
    "Desktop development with C++, current MSVC and the Windows SDK on Windows; "
    "g++/build-essential on Linux; or Xcode Command Line Tools on macOS. "
    "See https://brian2.readthedocs.io/en/stable/introduction/install.html ."
)


def run(command, *, cwd=None, timeout=1800, env=None):
    command = [str(part) for part in command]
    print("+", subprocess.list2cmdline(command), flush=True)
    return subprocess.run(command, cwd=cwd, env=env, check=True, timeout=timeout,
                          stdin=sys.stdin, stdout=sys.stdout, stderr=sys.stderr,
                          creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)


def digest(path):
    path = Path(path)
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_DOWNLOAD:
        raise RuntimeError(f"Expected a regular file of at most 64 MiB: {path}")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha512").hexdigest()


def validate_entry(entry):
    if (not isinstance(entry, dict) or not isinstance(entry.get("filename"), str)
            or not re.fullmatch(r"[A-Za-z0-9._+\-]{1,150}\.jar", entry["filename"])
            or not isinstance(entry.get("url"), str) or not entry["url"].startswith("https://")
            or not isinstance(entry.get("sha512"), str) or not re.fullmatch(r"[0-9a-f]{128}", entry["sha512"])):
        raise ValueError("Dependency entry needs an HTTPS URL, plain .jar filename and SHA-512 checksum")
    return entry


def backup(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise RuntimeError(f"Refusing to replace a symlink or non-file: {path}")
    suffix = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    destination = path.with_name(path.name + "." + suffix + ".bak")
    shutil.copy2(path, destination)
    print("Backup:", destination)
    return destination


def publish(temporary, path, *, replace=False):
    """Publish without a check-then-overwrite race when replacement was not selected."""
    if path.is_symlink():
        raise RuntimeError(f"Destination is a symlink: {path}")
    if replace:
        if path.exists():
            backup(path)
        os.replace(temporary, path)
    else:
        # Same-directory hard link is atomic and fails if another installer created the target.
        os.link(temporary, path)


def atomic_write(path, data, *, replace=False):
    path = Path(path)
    if path.is_symlink():
        raise RuntimeError(f"Destination is a symlink: {path}")
    if path.exists():
        if not path.is_file() or path.stat().st_size > MAX_DOWNLOAD:
            raise RuntimeError(f"Destination is not a bounded regular file: {path}")
        if path.read_bytes() == data:
            return
        if not replace:
            raise RuntimeError(f"Different file already exists: {path}; use --replace to keep a backup and replace it")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".flybrain-", suffix=".part", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        publish(temporary, path, replace=replace)
    finally:
        temporary.unlink(missing_ok=True)


def fetch(entry, destination, *, replace=False, retries=0):
    """No implicit retries, unbounded reads or installation of unverified bytes."""
    entry = validate_entry(entry)
    destination = Path(destination)
    if destination.is_symlink():
        raise RuntimeError(f"Destination is a symlink: {destination}")
    if destination.exists():
        if digest(destination) == entry["sha512"]:
            return destination
        if not replace:
            raise RuntimeError(f"Checksum differs for existing {destination}; use --replace to preserve a backup")
    if retries not in range(4):
        raise ValueError("retries must be 0..3")
    destination.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(retries + 1):
        descriptor, temporary = tempfile.mkstemp(prefix=".flybrain-", suffix=".part", dir=destination.parent)
        temporary = Path(temporary)
        try:
            started, size, checksum = time.monotonic(), 0, hashlib.sha512()
            request = urllib.request.Request(entry["url"], headers={"User-Agent": "FruitFly-setup/0.3.0"})
            with os.fdopen(descriptor, "wb") as output, urllib.request.urlopen(request, timeout=30) as response:
                if not response.geturl().startswith("https://"):
                    raise RuntimeError("Download redirected away from HTTPS")
                declared = response.headers.get("Content-Length")
                if declared is not None and (int(declared) < 0 or int(declared) > MAX_DOWNLOAD):
                    raise RuntimeError("Download exceeds 64 MiB limit")
                while chunk := response.read(64 * 1024):
                    size += len(chunk)
                    if size > MAX_DOWNLOAD or time.monotonic() - started > 180:
                        raise RuntimeError("Download exceeded its size or time limit")
                    checksum.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if checksum.hexdigest() != entry["sha512"]:
                raise RuntimeError(f"SHA-512 mismatch for {entry['filename']}; download was not installed")
            publish(temporary, destination, replace=replace)
            print("Verified:", destination)
            return destination
        except OSError:
            if attempt == retries:
                raise
            print(f"Download retry {attempt + 1}/{retries}: {entry['filename']}", flush=True)
        finally:
            temporary.unlink(missing_ok=True)


def mod_id(path):
    with zipfile.ZipFile(path) as jar:
        info = jar.getinfo("fabric.mod.json")
        if info.file_size > 64 * 1024:
            raise RuntimeError(f"Oversized mod metadata: {path}")
        return json.loads(jar.read(info))["id"]


def install_mod(source, mods, expected_id, filename, *, replace=False):
    source, mods = Path(source), Path(mods)
    if mod_id(source) != expected_id:
        raise RuntimeError(f"Wrong mod identity in {source}; expected {expected_id}")
    digest(source)  # Bound the local source before reading/copying it.
    mods.mkdir(parents=True, exist_ok=True)
    conflicts = []
    for existing in mods.glob("*.jar"):
        if existing.name == filename:
            continue
        try:
            if mod_id(existing) == expected_id:
                conflicts.append(existing)
        except (KeyError, ValueError, zipfile.BadZipFile):
            continue  # Unrelated non-Fabric jars are left untouched.
    if conflicts and not replace:
        raise RuntimeError(f"Another {expected_id} jar is installed: {conflicts}; close the game and use --replace or move it yourself")
    # Preflight destination first; do not remove an old version if the new destination conflicts.
    target, data = mods / filename, source.read_bytes()
    if target.exists() and digest(target) != hashlib.sha512(data).hexdigest() and not replace:
        raise RuntimeError(f"Different file already exists: {target}; use --replace")
    for old in conflicts:
        backup(old)
        old.unlink()
    atomic_write(target, data, replace=replace)
    print("Installed:", target)


def configure(game, python, script):
    path = Path(game) / "config/flybrain.json"
    if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_size > 1024 * 1024)):
        raise RuntimeError(f"Config must be a regular JSON file of at most 1 MiB: {path}")
    config = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    if not isinstance(config, dict):
        raise RuntimeError(f"Expected a JSON object at {path}; original left unchanged")
    updates = {"brainPython": str(Path(python).resolve()), "brainScript": str(Path(script).resolve()),
               "brainAutoStart": True, "simulationMode": "flywire-external", "fallback": "stop", "maxBrains": 1}
    if all(config.get(key) == value for key, value in updates.items()):
        return
    config.update(updates)
    atomic_write(path, (json.dumps(config, indent=2, allow_nan=False) + "\n").encode(), replace=True)
    print("Configured one server-side brain:", path)


def python314(selected=None):
    if selected:
        command = [selected]
    elif sys.version_info[:2] == (3, 14):
        command = [sys.executable]
    elif shutil.which("python3.14"):
        command = [shutil.which("python3.14")]
    elif os.name == "nt" and shutil.which("py"):
        command = [shutil.which("py"), "-3.14"]
    else:
        raise RuntimeError("Install 64-bit Python 3.14 from python.org and pass --python /path/to/python")
    result = subprocess.run(command + ["-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}:{sys.maxsize > 2**32}')"],
                            capture_output=True, text=True, check=True, timeout=20,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if result.stdout.strip() != "3.14:True":
        raise RuntimeError("The server brain needs 64-bit Python 3.14; choose it with --python")
    return command


def venv_python(root):
    return Path(root) / ".venv-flywire" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def check_brain(root, python):
    root = Path(root)
    (root / "docs/flywire-evidence").mkdir(parents=True, exist_ok=True)
    try:
        run([python, root / "brain-simulator/brain_server.py", "--check"], cwd=root, timeout=1200)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(COMPILER_HELP) from error


def prepare_brain(root, selected=None):
    root = Path(root)
    python = venv_python(root)
    if python.exists():
        python314(str(python))
    else:
        environment = root / ".venv-flywire"
        if environment.exists():
            raise RuntimeError(f"Incomplete environment at {environment}; rename it before running setup again")
        run(python314(selected) + ["-m", "venv", str(environment)], cwd=root)
    run([python, "-m", "pip", "install", "-r", root / "brain-simulator/requirements.txt"], cwd=root)
    check_brain(root, python)
    return python


def java21(selected=None):
    home = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if os.environ.get("JAVA_HOME") else None
    executable = selected or (str(home) if home and home.is_file() else shutil.which("java"))
    if not executable:
        raise RuntimeError("Install Java 21 and pass --java /path/to/java, or set JAVA_HOME")
    result = subprocess.run([str(executable), "-version"], capture_output=True, text=True, check=True, timeout=20,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    if not re.search(r'version "21(?:[.\"]|$)', result.stdout + result.stderr):
        raise RuntimeError("Use Java 21 for Minecraft 1.21.1; select its executable with --java")
    return str(executable)


def find_mod(root, lock, *, selected=None, java=None, release=False, retries=0, replace=False):
    root = Path(root); pinned = validate_entry(lock["flybrain"])
    if selected:
        source = Path(selected).resolve()
        digest(source)
        return source
    bundled = root / "lib" / pinned["filename"]
    if bundled.exists():
        if digest(bundled) != pinned["sha512"]:
            raise RuntimeError("Bundled FlyBrain checksum mismatch; use an unmodified ready-to-run archive")
        return bundled
    built = root / "build/libs" / pinned["filename"]
    if built.exists() and not release:
        digest(built)
        return built  # Locally built code is not expected to have the published binary's ZIP hash.
    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    if wrapper.exists() and not release:
        executable = java21(java)
        env = os.environ.copy()
        resolved = Path(shutil.which(executable) or executable).resolve()
        env["JAVA_HOME"] = str(resolved.parent.parent)
        run([executable, "-classpath", wrapper, "org.gradle.wrapper.GradleWrapperMain", "build", "--console=plain"], cwd=root, env=env)
        if not built.is_file():
            raise RuntimeError(f"Build did not produce {built}")
        digest(built)
        return built
    return fetch(pinned, root / ".cache" / pinned["filename"], retries=retries, replace=replace)


def create_server(root, java, installer):
    server = Path(root) / "server"
    marker = server / ".flybrain-managed.json"
    expected = {"minecraft": MINECRAFT, "loader": LOADER}
    if marker.exists():
        if json.loads(marker.read_text()) != expected:
            raise RuntimeError("Managed server versions differ; choose a separate directory")
        if not (server / "fabric-server-launch.jar").is_file():
            raise RuntimeError("Managed server launcher is missing; restore it from your backup")
        return server
    if server.exists() and any(server.iterdir()):
        raise RuntimeError(f"Existing unrecognized server directory left unchanged: {server}; use --game-dir for an existing Fabric instance")
    server.mkdir(parents=True, exist_ok=True)
    run([java, "-jar", installer, "server", "-mcversion", MINECRAFT, "-loader", LOADER, "-downloadMinecraft"], cwd=server)
    if not (server / "fabric-server-launch.jar").is_file():
        raise RuntimeError("Fabric installer did not produce fabric-server-launch.jar; inspect its output")
    atomic_write(marker, (json.dumps(expected, indent=2) + "\n").encode())
    if not (server / "eula.txt").exists():
        atomic_write(server / "eula.txt", b"# Read https://www.minecraft.net/eula before making your own choice.\neula=false\n")
    return server


def run_server(server, java):
    server = Path(server).resolve()
    eula = server / "eula.txt"
    accepted = eula.exists() and any(line.strip().lower() == "eula=true" for line in eula.read_text().splitlines())
    if not accepted:
        raise RuntimeError(f"Read https://www.minecraft.net/eula and make your own choice in {eula}; setup never accepts it")
    launcher = server / "fabric-server-launch.jar"
    if not launcher.is_file():
        raise RuntimeError(f"No Fabric server launcher at {launcher}; run setup --server first")
    run([java, "-Xms1G", "-Xmx4G", "-jar", launcher, "nogui"], cwd=server, timeout=None)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("setup", help="install into an explicit Fabric instance, or create ./server")
    destination = setup.add_mutually_exclusive_group(required=True)
    destination.add_argument("--game-dir", type=Path)
    destination.add_argument("--server", action="store_true")
    setup.add_argument("--client", action="store_true", help="mods only; no neural environment or config changes")
    setup.add_argument("--voicechat", action="store_true", help="also install pinned optional Simple Voice Chat")
    setup.add_argument("--python", help="64-bit Python 3.14 executable for the server brain")
    setup.add_argument("--java", help="Java 21 executable when building or creating a server")
    setup.add_argument("--mod-jar", type=Path, help="explicit local build to install instead of the pinned release")
    setup.add_argument("--release", action="store_true", help="download the pinned release instead of building absent local artifacts")
    setup.add_argument("--replace", action="store_true", help="back up conflicting mod files before replacing them")
    setup.add_argument("--retries", type=int, choices=range(4), default=0, help="explicit download retries (default: none)")
    commands.add_parser("check", help="rerun the real neural check in the existing local environment")
    server = commands.add_parser("run-server", help="run ./server without a GUI; never accepts the EULA")
    server.add_argument("--java")
    server.add_argument("--game-dir", type=Path, default=ROOT / "server")
    args = parser.parse_args(argv)
    try:
        if args.command == "check":
            python = venv_python(ROOT)
            python314(str(python)); check_brain(ROOT, python)
            return 0
        if args.command == "run-server":
            run_server(args.game_dir, java21(args.java))
            return 0
        if args.client and args.server:
            parser.error("--client installs into --game-dir; --server is the neural server host")
        lock = json.loads((ROOT / "deps-lock.json").read_text(encoding="utf-8"))
        game = args.game_dir.resolve() if args.game_dir else ROOT / "server"
        if args.server:
            installer = fetch(lock["fabric-installer"], ROOT / ".cache" / lock["fabric-installer"]["filename"], retries=args.retries, replace=args.replace)
            game = create_server(ROOT, java21(args.java), installer)
        mod = find_mod(ROOT, lock, selected=args.mod_jar, java=args.java, release=args.release, retries=args.retries, replace=args.replace)
        install_mod(mod, game / "mods", "flybrain", lock["flybrain"]["filename"], replace=args.replace)
        for name in ["fabric-api"] + (["voicechat"] if args.voicechat else []):
            dependency = fetch(lock[name], ROOT / ".cache" / lock[name]["filename"], retries=args.retries, replace=args.replace)
            install_mod(dependency, game / "mods", name, lock[name]["filename"], replace=args.replace)
        if not args.client:
            python = prepare_brain(ROOT, args.python)
            configure(game, python, ROOT / "brain-simulator/brain_server.py")
        print("Setup complete:", game)
        if args.server:
            print("Read https://www.minecraft.net/eula; edit server/eula.txt yourself, then: python manage.py run-server")
        else:
            print(f"Select a Fabric {MINECRAFT} / loader {LOADER} profile using this game directory in your launcher. No launcher profiles were modified.")
        if args.client:
            print("Client mods only. The host runs the single shared brain; this client needs no neural dependencies.")
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Setup failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
