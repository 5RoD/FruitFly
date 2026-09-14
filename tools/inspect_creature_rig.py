"""Read-only inventory of the supplied armature in Blender world coordinates."""
import bpy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ROOT / 'src/main/resources/assets/flybrain/models/creature.glb'))
rig = next(o for o in bpy.context.scene.objects if o.type == 'ARMATURE')
mesh = next(o for o in bpy.context.scene.objects if o.type == 'MESH')
rows = []
for bone in rig.data.bones:
    group = mesh.vertex_groups.get(bone.name)
    points = []
    if group:
        for v in mesh.data.vertices:
            if any(g.group == group.index and g.weight > .5 for g in v.groups):
                points.append(mesh.matrix_world @ v.co)
    row = dict(name=bone.name, parent=bone.parent.name if bone.parent else None,
               head=list(rig.matrix_world @ bone.head_local), tail=list(rig.matrix_world @ bone.tail_local),
               vertices=len(points), bounds=[[min(p[i] for p in points),max(p[i] for p in points)] for i in range(3)] if points else [])
    rows.append(row)
    print(bone.name, 'head',tuple(round(v,3) for v in row['head']), 'tail',tuple(round(v,3) for v in row['tail']), 'vertices',len(points),row['bounds'])
out = ROOT / 'docs/animation-evidence'
out.mkdir(parents=True, exist_ok=True)
(out / 'rig-inventory.json').write_text(json.dumps(dict(armature=rig.name,mesh=mesh.name,bones=rows),indent=2))
