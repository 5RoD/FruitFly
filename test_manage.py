"""Portable installer checks; no internet, game launch, pip install or neural run."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import manage


def jar_bytes(mod_id="flybrain", version="0.3.0"):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as jar:
        jar.writestr("fabric.mod.json", json.dumps({"id": mod_id, "version": version}))
    return stream.getvalue()


def entry(data, filename="test.jar"):
    return {"filename": filename, "url": "https://example.test/" + filename,
            "sha512": hashlib.sha512(data).hexdigest()}


class Response(io.BytesIO):
    def __init__(self, data):
        super().__init__(data)
        self.headers = {"Content-Length": str(len(data))}

    def geturl(self):
        return "https://example.test/test.jar"


class ManageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_real_child_stdout_stderr_and_stop_input_are_forwarded(self):
        child = "import sys; print('child-out', flush=True); print('child-error', file=sys.stderr, flush=True); print('received:' + input(), flush=True)"
        program = f"import sys, manage; manage.run([sys.executable, '-c', {child!r}], timeout=10)"
        result = subprocess.run([sys.executable, "-c", program], input="stop\n", capture_output=True,
                                text=True, timeout=20, cwd=Path(manage.__file__).parent)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("\nchild-out\n", result.stdout)
        self.assertIn("received:stop", result.stdout)
        self.assertIn("child-error", result.stderr)

    def test_download_verifies_and_publishes_atomically(self):
        data = jar_bytes()
        target = self.root / "mods/test.jar"
        with patch("manage.urllib.request.urlopen", return_value=Response(data)):
            manage.fetch(entry(data), target)
        self.assertEqual(target.read_bytes(), data)
        self.assertEqual(list(target.parent.glob("*.part")), [])
        with patch("manage.urllib.request.urlopen") as request:
            manage.fetch(entry(data), target)
            request.assert_not_called()

    def test_corrupt_download_never_publishes_or_overwrites(self):
        target = self.root / "test.jar"
        with patch("manage.urllib.request.urlopen", return_value=Response(b"corrupt")):
            with self.assertRaises(RuntimeError):
                manage.fetch(entry(b"expected"), target)
        self.assertFalse(target.exists())
        target.write_bytes(b"old")
        with self.assertRaises(RuntimeError):
            manage.fetch(entry(b"new"), target)
        self.assertEqual(target.read_bytes(), b"old")

    def test_explicit_replace_preserves_old_file(self):
        target = self.root / "test.jar"
        target.write_bytes(b"old")
        with patch("manage.urllib.request.urlopen", return_value=Response(b"new")):
            manage.fetch(entry(b"new"), target, replace=True)
        self.assertEqual(target.read_bytes(), b"new")
        self.assertTrue(any(path.read_bytes() == b"old" for path in self.root.glob("*.bak")))

    def test_manifest_and_download_are_bounded(self):
        for change in ({"filename": "../escape.jar"}, {"filename": "C:\\escape.jar"},
                       {"url": "http://example.test/a.jar"}, {"sha512": "bad"}):
            bad = entry(b"ok") | change
            with self.assertRaises((ValueError, RuntimeError)):
                manage.fetch(bad, self.root / "target.jar")
        with patch.object(manage, "MAX_DOWNLOAD", 3), patch("manage.urllib.request.urlopen", return_value=Response(b"large")):
            with self.assertRaises(RuntimeError):
                manage.fetch(entry(b"large"), self.root / "target.jar")
        self.assertFalse((self.root / "target.jar").exists())

    def test_retry_only_when_explicit(self):
        target = self.root / "test.jar"
        with patch("manage.urllib.request.urlopen", side_effect=[OSError("offline"), Response(b"ok")]) as request:
            manage.fetch(entry(b"ok"), target, retries=1)
        self.assertEqual(request.call_count, 2)
        self.assertEqual(target.read_bytes(), b"ok")

    def test_concurrent_download_destination_is_not_overwritten(self):
        target = self.root / "test.jar"
        def response(*args, **kwargs):
            target.write_bytes(b"concurrent owner's file")
            return Response(b"download")
        with patch("manage.urllib.request.urlopen", side_effect=response):
            with self.assertRaises((RuntimeError, FileExistsError)):
                manage.fetch(entry(b"download"), target)
        self.assertEqual(target.read_bytes(), b"concurrent owner's file")
        self.assertEqual(list(self.root.glob("*.part")), [])

    def test_config_read_is_bounded_and_preserves_large_input(self):
        config = self.root / "config/flybrain.json"
        config.parent.mkdir()
        config.write_bytes(b" " * (1024 * 1024 + 1))
        with self.assertRaises(RuntimeError):
            manage.configure(self.root, self.root / "python", self.root / "brain.py")
        self.assertEqual(config.stat().st_size, 1024 * 1024 + 1)

    def test_redirect_stream_bound_and_exhausted_retry_leave_no_partial_file(self):
        target = self.root / "test.jar"
        response = Response(b"abc")
        response.geturl = lambda: "http://example.test/test.jar"
        with patch("manage.urllib.request.urlopen", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "HTTPS"):
                manage.fetch(entry(b"abc"), target)
        response = Response(b"abc")
        response.headers = {}
        with patch.object(manage, "MAX_DOWNLOAD", 2), patch("manage.urllib.request.urlopen", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "size or time"):
                manage.fetch(entry(b"abc"), target)
        with patch("manage.urllib.request.urlopen", side_effect=OSError("offline")) as request:
            with self.assertRaises(OSError):
                manage.fetch(entry(b"abc"), target, retries=1)
            self.assertEqual(request.call_count, 2)
        self.assertFalse(target.exists())
        self.assertEqual(list(self.root.glob("*.part")), [])

    def test_config_uses_actual_filename_preserves_fields_and_backup(self):
        config = self.root / "game/config/flybrain.json"
        config.parent.mkdir(parents=True)
        original = {"debug": False, "ambienceVolume": 0.2, "externalPort": 9000, "custom": {"keep": True}}
        config.write_text(json.dumps(original), encoding="utf-8")
        python = self.root / "venv/bin/python"
        script = self.root / "brain-simulator/brain_server.py"
        manage.configure(self.root / "game", python, script)
        result = json.loads(config.read_text())
        self.assertEqual(result["brainPython"], str(python.resolve()))
        self.assertEqual(result["brainScript"], str(script.resolve()))
        self.assertTrue(result["brainAutoStart"])
        self.assertEqual(result["externalPort"], 9000)
        self.assertEqual(result["custom"], original["custom"])
        self.assertTrue(any(json.loads(path.read_text()) == original for path in config.parent.glob("*.bak")))
        before = sorted(config.parent.glob("*.bak"))
        manage.configure(self.root / "game", python, script)
        self.assertEqual(sorted(config.parent.glob("*.bak")), before)

    def test_malformed_config_is_not_replaced(self):
        config = self.root / "config/flybrain.json"
        config.parent.mkdir()
        config.write_text("[]")
        with self.assertRaises(RuntimeError):
            manage.configure(self.root, self.root / "python", self.root / "brain.py")
        self.assertEqual(config.read_text(), "[]")

    def test_client_setup_skips_python_neural_and_config_changes(self):
        data = jar_bytes()
        source = self.root / "lib/flybrain-0.3.0.jar"
        source.parent.mkdir(); source.write_bytes(data)
        lock = {"flybrain": entry(data, source.name), "fabric-api": entry(jar_bytes("fabric-api"), "fabric-api.jar")}
        (self.root / "deps-lock.json").write_text(json.dumps(lock))
        game = self.root / "game"
        dependency = self.root / "fabric-api.jar"
        dependency.write_bytes(jar_bytes("fabric-api"))
        with patch.object(manage, "ROOT", self.root), patch("manage.fetch", return_value=dependency) as fetch, patch("manage.prepare_brain") as brain:
            self.assertEqual(manage.main(["setup", "--client", "--game-dir", str(game)]), 0)
            brain.assert_not_called()
            self.assertEqual(fetch.call_count, 1)
        self.assertTrue((game / "mods" / source.name).exists())
        self.assertFalse((game / "config/flybrain.json").exists())

    def test_host_setup_verifies_brain_before_configuring_and_optional_voice(self):
        lock = {}
        sources = {}
        for name in ("flybrain", "fabric-api", "voicechat"):
            source = self.root / (name + ".jar")
            source.write_bytes(jar_bytes(name))
            sources[name] = source
            lock[name] = entry(source.read_bytes(), source.name)
        (self.root / "deps-lock.json").write_text(json.dumps(lock))
        game = self.root / "game"
        python = self.root / "python"
        with patch.object(manage, "ROOT", self.root), patch("manage.find_mod", return_value=sources["flybrain"]), \
                patch("manage.fetch", side_effect=[sources["fabric-api"], sources["voicechat"]]), \
                patch("manage.prepare_brain", return_value=python) as brain:
            self.assertEqual(manage.main(["setup", "--game-dir", str(game), "--voicechat", "--replace"]), 0)
            brain.assert_called_once_with(self.root, None)
        self.assertEqual(len(list((game / "mods").glob("*.jar"))), 3)
        self.assertEqual(json.loads((game / "config/flybrain.json").read_text())["brainPython"], str(python.resolve()))
        (game / "config/flybrain.json").unlink()
        with patch.object(manage, "ROOT", self.root), patch("manage.find_mod", return_value=sources["flybrain"]), \
                patch("manage.fetch", return_value=sources["fabric-api"]), \
                patch("manage.prepare_brain", side_effect=RuntimeError("brain check failed")):
            self.assertEqual(manage.main(["setup", "--game-dir", str(game)]), 1)
        self.assertFalse((game / "config/flybrain.json").exists())

    def test_interpreter_and_java_versions_are_checked(self):
        for output in ("3.13:True", "3.14:False"):
            with patch("manage.subprocess.run", return_value=subprocess.CompletedProcess([], 0, output, "")):
                with self.assertRaises(RuntimeError):
                    manage.python314("python-fixture")
        with patch("manage.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "3.14:True", "")):
            self.assertEqual(manage.python314("python-fixture"), ["python-fixture"])
        for version in ("17.0.1", "22.0.1", "121.0.1"):
            with patch("manage.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "", f'openjdk version "{version}"')):
                with self.assertRaises(RuntimeError):
                    manage.java21("java-fixture")
        with patch("manage.subprocess.run", return_value=subprocess.CompletedProcess([], 0, "", 'openjdk version "21.0.8"')):
            self.assertEqual(manage.java21("java-fixture"), "java-fixture")

    def test_neural_environment_creation_reuse_and_incomplete_refusal(self):
        python = manage.venv_python(self.root)
        with patch("manage.python314", return_value=["python-fixture"]), patch("manage.run") as run, patch("manage.check_brain") as check:
            self.assertEqual(manage.prepare_brain(self.root), python)
            self.assertIn("venv", run.call_args_list[0].args[0])
            self.assertIn("pip", run.call_args_list[1].args[0])
            check.assert_called_once_with(self.root, python)
        python.parent.mkdir(parents=True)
        with patch("manage.run") as run:
            with self.assertRaisesRegex(RuntimeError, "Incomplete"):
                manage.prepare_brain(self.root)
            run.assert_not_called()
        python.write_bytes(b"fixture")
        with patch("manage.python314") as probe, patch("manage.run") as run, patch("manage.check_brain"):
            manage.prepare_brain(self.root)
            probe.assert_called_once_with(str(python))
            self.assertEqual(run.call_count, 1)

    def test_mod_selection_prefers_verified_bundle_then_local_build(self):
        data = jar_bytes()
        lock = {"flybrain": entry(data, "flybrain-0.3.0.jar")}
        built = self.root / "build/libs/flybrain-0.3.0.jar"
        built.parent.mkdir(parents=True); built.write_bytes(data)
        with patch("manage.fetch") as fetch:
            self.assertEqual(manage.find_mod(self.root, lock), built)
            fetch.assert_not_called()
        bundled = self.root / "lib/flybrain-0.3.0.jar"
        bundled.parent.mkdir(); bundled.write_bytes(data)
        self.assertEqual(manage.find_mod(self.root, lock), bundled)
        bundled.write_bytes(b"tampered")
        with self.assertRaisesRegex(RuntimeError, "checksum"):
            manage.find_mod(self.root, lock)
        self.assertEqual(manage.find_mod(self.root, lock, selected=built), built.resolve())
        bundled.unlink(); built.unlink()
        with patch("manage.fetch", return_value=built) as fetch:
            self.assertEqual(manage.find_mod(self.root, lock, release=True, retries=2, replace=True), built)
            self.assertEqual(fetch.call_args.kwargs, {"retries": 2, "replace": True})

    def test_local_gradle_build_uses_java21_without_shell(self):
        wrapper = self.root / "gradle/wrapper/gradle-wrapper.jar"
        wrapper.parent.mkdir(parents=True); wrapper.write_bytes(b"wrapper")
        built = self.root / "build/libs/flybrain-0.3.0.jar"
        def build(command, **kwargs):
            self.assertIn("org.gradle.wrapper.GradleWrapperMain", command)
            self.assertIn("JAVA_HOME", kwargs["env"])
            built.parent.mkdir(parents=True); built.write_bytes(jar_bytes())
        with patch("manage.java21", return_value=str(self.root / "jdk/bin/java")), patch("manage.run", side_effect=build):
            self.assertEqual(manage.find_mod(self.root, {"flybrain": entry(jar_bytes(), built.name)}), built)

    def test_wrong_mod_identity_and_invalid_archive_leave_mods_untouched(self):
        source = self.root / "wrong.jar"; source.write_bytes(jar_bytes("unrelated"))
        with self.assertRaisesRegex(RuntimeError, "identity"):
            manage.install_mod(source, self.root / "mods", "flybrain", "flybrain.jar")
        self.assertFalse((self.root / "mods").exists())
        source.write_bytes(b"broken zip")
        with self.assertRaises(zipfile.BadZipFile):
            manage.install_mod(source, self.root / "mods", "flybrain", "flybrain.jar")

    def test_duplicate_mod_rejected_without_replace(self):
        mods = self.root / "mods"; mods.mkdir()
        old = mods / "flybrain-old.jar"; old.write_bytes(jar_bytes(version="0.2.0"))
        new = self.root / "flybrain-0.3.0.jar"; new.write_bytes(jar_bytes())
        with self.assertRaises(RuntimeError):
            manage.install_mod(new, mods, "flybrain", new.name)
        self.assertTrue(old.exists())
        manage.install_mod(new, mods, "flybrain", new.name, replace=True)
        self.assertFalse(old.exists())
        self.assertTrue((mods / new.name).exists())
        self.assertTrue(list(mods.glob("*.bak")))

    def test_check_creates_output_directory_and_uses_real_script_flag(self):
        script = self.root / "brain-simulator/brain_server.py"
        script.parent.mkdir(); script.write_text("# fixture")
        with patch("manage.run") as run:
            manage.check_brain(self.root, self.root / "python")
        self.assertTrue((self.root / "docs/flywire-evidence").is_dir())
        self.assertEqual(run.call_args.args[0][-1], "--check")
        self.assertEqual(Path(run.call_args.args[0][-2]), script)

    def test_compiler_failure_has_actionable_error_and_no_fallback(self):
        with patch("manage.run", side_effect=subprocess.CalledProcessError(1, ["python", "brain_server.py", "--check"])):
            with self.assertRaisesRegex(RuntimeError, "C\\+\\+|MSVC"):
                manage.check_brain(self.root, self.root / "python")

    def test_server_never_accepts_eula_or_touches_arbitrary_directory(self):
        server = self.root / "server"
        installer = self.root / "installer.jar"; installer.write_bytes(b"verified fixture")
        def install(command, **kwargs):
            self.assertIn("-downloadMinecraft", command)
            (server / "fabric-server-launch.jar").write_bytes(b"fixture")
        with patch("manage.run", side_effect=install):
            manage.create_server(self.root, "java", installer)
        self.assertIn("eula=false", (server / "eula.txt").read_text())
        with patch("manage.run") as launch:
            with self.assertRaises(RuntimeError):
                manage.run_server(server, "java")
            launch.assert_not_called()
        (server / "eula.txt").write_text("eula=true\n")
        with patch("manage.run") as launch:
            manage.run_server(server, "java")
            self.assertIn("nogui", launch.call_args.args[0])
        unrelated = self.root / "unrelated/server"; unrelated.mkdir(parents=True)
        (unrelated / "notes.txt").write_text("keep")
        with self.assertRaises(RuntimeError):
            manage.create_server(unrelated.parent, "java", installer)
        self.assertEqual((unrelated / "notes.txt").read_text(), "keep")


if __name__ == "__main__":
    unittest.main()
