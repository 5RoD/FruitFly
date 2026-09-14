"""Compare the editable Blender bake against GLB keys without saving either asset.

Run with Blender: --background art/creature/creature-animations.blend
--disable-autoexec --python-exit-code 1 --python tools/check_animation_bake.py
"""
import hashlib
import json
import math
from pathlib import Path
import struct

import bpy
from mathutils import Matrix, Quaternion, Vector

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/flybrain/models"
TOLERANCE = 0.0001


def read_glb(path: Path) -> tuple[dict, bytes]:
    data = path.read_bytes()
    magic, version, length, size, kind = struct.unpack_from("<5I", data)
    assert (magic, version, length, kind) == (0x46546C67, 2, len(data), 0x4E4F534A)
    bin_size, bin_kind = struct.unpack_from("<2I", data, 20 + size)
    binary = data[28 + size:]
    assert bin_kind == 0x004E4942 and bin_size == len(binary)
    return json.loads(data[20:20 + size]), binary


def world_matrices(nodes: list[dict]) -> dict[int, Matrix]:
    parents = {child: i for i, node in enumerate(nodes) for child in node.get("children", [])}
    world = {}

    def resolve(i: int) -> Matrix:
        if i not in world:
            node = nodes[i]
            assert "matrix" not in node, "This supplied rig uses TRS nodes"
            x, y, z, w = node.get("rotation", [0, 0, 0, 1])
            local = Matrix.LocRotScale(Vector(node.get("translation", [0, 0, 0])),
                                       Quaternion((w, x, y, z)), Vector(node.get("scale", [1, 1, 1])))
            world[i] = resolve(parents[i]) @ local if i in parents else local
        return world[i]

    for i in range(len(nodes)):
        resolve(i)
    return world


def compare_clip(rig: bpy.types.Object, document: dict, binary: bytes,
                 animation: dict, rest: dict[int, Matrix]) -> list[dict]:
    rig.animation_data.action = bpy.data.actions[animation["name"]]
    count = document["accessors"][animation["samplers"][0]["input"]]["count"]
    for index in {sampler["input"] for sampler in animation["samplers"]}:
        accessor = document["accessors"][index]
        view = document["bufferViews"][accessor["bufferView"]]
        assert accessor["type"] == "SCALAR" and accessor["componentType"] == 5126 and accessor["count"] == count
        offset = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
        for frame in range(count):
            seconds = struct.unpack_from("<f", binary, offset + frame * view.get("byteStride", 4))[0]
            expected_seconds = frame * bpy.context.scene.render.fps_base / bpy.context.scene.render.fps
            assert abs(seconds - expected_seconds) < 0.00001, "Blender/GLB key timing differs"
    convert = Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1)))
    results = []
    for frame in (range(count) if animation["name"] in {"walk", "run"} else (0, count // 2, count - 1)):
        bpy.context.scene.frame_set(frame)
        bpy.context.view_layer.update()
        nodes = [dict(node) for node in document["nodes"]]
        for channel in animation["channels"]:
            sampler = animation["samplers"][channel["sampler"]]
            accessor = document["accessors"][sampler["output"]]
            view = document["bufferViews"][accessor["bufferView"]]
            path = channel["target"]["path"]
            assert path in {"rotation", "translation"}
            width = 4 if path == "rotation" else 3
            cubic = sampler.get('interpolation') == 'CUBICSPLINE'
            assert accessor["componentType"] == 5126 and accessor["count"] == count*(3 if cubic else 1)
            stride = view.get("byteStride", width * 4)
            offset = view.get("byteOffset", 0) + accessor.get("byteOffset", 0) + (frame*3+1 if cubic else frame) * stride
            nodes[channel["target"]["node"]][path] = list(struct.unpack_from("<" + "f" * width, binary, offset))
        gltf = world_matrices(nodes)
        errors = []
        for i in document["skins"][0]["joints"]:
            bone = rig.pose.bones[document["nodes"][i]["name"]]
            expected = (convert @ rig.matrix_world @ bone.matrix @ bone.bone.matrix_local.inverted()
                        @ rig.matrix_world.inverted() @ convert.inverted() @ rest[i])
            errors.extend(abs(expected[row][col] - gltf[i][row][col]) for row in range(4) for col in range(4))
        assert all(math.isfinite(error) and error < TOLERANCE for error in errors), (animation["name"], frame, max(errors))
        results.append({"frame": frame, "max_matrix_error": max(errors)})
    return results


def main() -> None:
    blend = ROOT / "art/creature/creature-animations.blend"
    assert Path(bpy.data.filepath).resolve() == blend.resolve(), "Load the authored blend file first"
    original, original_binary = read_glb(MODELS / "creature.glb")
    document, binary = read_glb(MODELS / "creature-animated.glb")
    assert binary[:len(original_binary)] == original_binary, "Original binary changed"
    repaired, repaired_binary = read_glb(ROOT / "art/creature/creature-motion-rigged.glb")
    assert binary[:len(repaired_binary)] == repaired_binary, "Approved repair binary changed"
    assert document["meshes"] == repaired["meshes"], "Additional mesh edits beyond approved weights"
    mesh = json.loads(json.dumps(document["meshes"]))
    for name in ("JOINTS_0", "WEIGHTS_0"):
        mesh[0]["primitives"][0]["attributes"][name] = original["meshes"][0]["primitives"][0]["attributes"][name]
    assert mesh == original["meshes"], "Non-weight mesh edit"
    for key in original.keys() - {"animations", "accessors", "bufferViews", "buffers", "meshes"}:
        assert document[key] == original[key], f"Original {key} changed"
    for key in ("accessors", "bufferViews"):
        assert document[key][:len(original[key])] == original[key], f"Original {key} changed"
    assert len(document["animations"]) == 10
    assert {clip["name"] for clip in document["animations"]} == {
        "idle", "walk", "run", "takeoff", "air", "land", "strike", "hurt", "death", "groom"}
    rig = next(obj for obj in bpy.context.scene.objects if obj.type == "ARMATURE")
    rest = world_matrices(original["nodes"])
    clips = {clip["name"]: compare_clip(rig, document, binary, clip, rest) for clip in document["animations"]}
    report = {"status": "PASS", "blender_version": bpy.app.version_string,
              "matrix_tolerance": TOLERANCE, "original_binary_and_rig_preserved": True,
              "joint_count": len(document["skins"][0]["joints"]), "clips": clips,
              "coverage": "All 56 joint matrices at every walk/run key and first/middle/final keys of other clips; not visual quality",
              "sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in (blend, MODELS / "creature.glb", MODELS / "creature-animated.glb")}}
    target = ROOT / "docs/animation-evidence/bake-parity.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert json.loads(target.read_text(encoding="utf-8")) == report
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

