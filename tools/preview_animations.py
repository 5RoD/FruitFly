"""Reopen the delivered Blender actions and render representative frames for visual QA."""
from pathlib import Path
import json
import sys
import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = '--runtime' in sys.argv
GAIT = '--gait' in sys.argv or RUNTIME
OUT = ROOT / ('docs/gait-refinement' if GAIT else 'docs/animation-evidence')
bpy.ops.wm.open_mainfile(filepath=str(ROOT / 'art/creature/creature-animations.blend'))
scene = bpy.context.scene
rig = next(o for o in scene.objects if o.type == 'ARMATURE')
scene.render.engine = 'BLENDER_EEVEE'
scene.render.resolution_x = scene.render.resolution_y = 512
scene.render.resolution_percentage = 100
if scene.world is None:
    scene.world = bpy.data.worlds.new('Preview world')
scene.world.color = (.18, .18, .18)
for location, power, size in [((3,-4,5),600,4),((-3,-2,3),350,3),((0,4,4),500,3)]:
    bpy.ops.object.light_add(type='AREA', location=location)
    light = bpy.context.object
    light.data.energy = power
    light.data.size = size
    light.rotation_euler = (Vector((0,0,.85))-light.location).to_track_quat('-Z','Y').to_euler()
bpy.ops.object.camera_add(location=(2.8,-4,2.2))
camera = bpy.context.object
camera.rotation_euler = (Vector((0,0,.85))-camera.location).to_track_quat('-Z','Y').to_euler()
camera.data.type = 'ORTHO'
camera.data.ortho_scale = 2.5
scene.camera = camera
bpy.ops.mesh.primitive_plane_add(size=200, location=(0,0,-.005))
floor = bpy.context.object
floor.name = 'Preview floor (not exported)'
material = bpy.data.materials.new('Preview floor')
material.diffuse_color = (.06,.065,.075,1)
floor.data.materials.append(material)
# A patterned floor exposes sliding that a featureless backdrop hides.
material.use_nodes = True
nodes = material.node_tree.nodes
checker = nodes.new('ShaderNodeTexChecker')
checker.inputs['Color1'].default_value = (.075,.085,.095,1)
checker.inputs['Color2'].default_value = (.14,.15,.16,1)
checker.inputs['Scale'].default_value = 4
coordinates = nodes.new('ShaderNodeTexCoord')
material.node_tree.links.new(coordinates.outputs['Object'],checker.inputs['Vector'])
material.node_tree.links.new(checker.outputs['Color'],nodes.get('Principled BSDF').inputs['Base Color'])
report = {}
shots = [('walk',[0,18,36,54]),('run',[0,9,18,27])] if GAIT else [('idle',[30]),('walk',[0,9,18,27]),('run',[5]),('takeoff',[5]),('air',[5]),('land',[5]),('strike',[8]),('hurt',[5]),('death',[30])]
if RUNTIME: shots=[]
elif GAIT:
    shots=[(clip,[round(frame*scene.render.fps/60) for frame in frames]) for clip,frames in shots]
for clip, frames in shots:
    action = bpy.data.actions[clip]
    rig.animation_data.action = action
    rig.animation_data.action_slot = action.slots[0]
    report[clip] = {'frame_range':list(action.frame_range), 'slots':len(action.slots)}
    for frame in frames:
        scene.frame_set(frame)
        scene.render.filepath = str(OUT / f'{clip}-{frame:03}.png')
        bpy.ops.render.render(write_still=True)
if GAIT and not RUNTIME:
    for label, location in [('side',(4,0,1.5)),('rear',(0,4,1.8))]:
        camera.location = location
        camera.rotation_euler = (Vector((0,0,.8))-camera.location).to_track_quat('-Z','Y').to_euler()
        rig.animation_data.action = bpy.data.actions['walk']
        rig.animation_data.action_slot = rig.animation_data.action.slots[0]
        for frame in (0,18,36,54):
            scene.frame_set(frame)
            scene.render.filepath = str(OUT / f'walk-{label}-{frame:03}.png')
            bpy.ops.render.render(write_still=True)
(OUT / 'reopened-actions.json').write_text(json.dumps(report,indent=2))
print('REOPEN AND RENDER PASS',len(report),'actions')
if RUNTIME:
    # QA surface uses the actual Java-skinned vertices and unchanged source triangles/UVs.
    # It is not an exported replacement model and does not drive Minecraft or the brain.
    import numpy as np
    sys.path.insert(0,str(ROOT/'tools'))
    from repair_creature_weights import read_glb,accessor
    document,binary,_=read_glb(ROOT/'src/main/resources/assets/flybrain/models/creature-animated.glb')
    primitive=document['meshes'][0]['primitives'][0]
    uv=accessor(document,binary,primitive['attributes']['TEXCOORD_0'])
    indices=accessor(document,binary,primitive['indices']).reshape(-1,3)
    positions=np.fromfile(OUT/'runtime-curve.f32',dtype='<f4').reshape(-1,len(uv),3)
    positions=positions[:,:,[0,2,1]].copy(); positions[:,:,1]*=-1
    original_mesh=next(o for o in scene.objects if o.type=='MESH' and o!=floor)
    surface=bpy.data.meshes.new('Java evaluated surface (QA only)')
    surface.from_pydata(positions[0].tolist(),[],indices.tolist())
    layer=surface.uv_layers.new()
    for polygon in surface.polygons:
        polygon.use_smooth=True
        for loop in polygon.loop_indices:
            vertex=surface.loops[loop].vertex_index
            layer.data[loop].uv=(float(uv[vertex,0]),1-float(uv[vertex,1]))
    surface.materials.append(original_mesh.data.materials[0])
    original_mesh.hide_render=True
    obj=bpy.data.objects.new('Java runtime contact QA',surface); scene.collection.objects.link(obj)
    frames_dir=OUT/'runtime-frames';frames_dir.mkdir(exist_ok=True)
    for i,vertices in enumerate(positions):
        surface.vertices.foreach_set('co',vertices.reshape(-1));surface.update()
        center=Vector((float(vertices[:,0].mean()),float(vertices[:,1].mean()),.8))
        camera.location=center+Vector((2.8,-4,1.4))
        camera.rotation_euler=(center-camera.location).to_track_quat('-Z','Y').to_euler()
        scene.render.filepath=str(frames_dir/f'{i:04}.png')
        bpy.ops.render.render(write_still=True)
    print('JAVA RUNTIME SURFACE RENDER',len(positions),'frames at 30 fps')
if '--motion' in sys.argv:
    frames_dir = OUT / 'motion-frames'
    frames_dir.mkdir(exist_ok=True)
    index = 0
    timeline = []
    if GAIT:
        camera.location = (2.8,-4,2.2)
        camera.rotation_euler = (Vector((0,0,.8))-camera.location).to_track_quat('-Z','Y').to_euler()
    sequence = [('walk',3),('run',3)] if GAIT else [('idle',1),('walk',2),('run',2),('takeoff',1),('air',2),('land',1),('strike',1),('hurt',1),('death',1)]
    output_fps = 30 if GAIT else 15
    for clip, repeats in sequence:
        action = bpy.data.actions[clip]
        rig.animation_data.action = action
        rig.animation_data.action_slot = action.slots[0]
        fps = scene.render.fps / scene.render.fps_base
        duration = action.frame_range[1] / fps
        playback_cycle = (.48 if clip=='walk' else .30) if GAIT else duration
        timeline.append({'clip':clip,'start_seconds':index/output_fps,'cycle_seconds':playback_cycle})
        for sample in range(round(playback_cycle*repeats*output_fps)):
            cycle = sample/output_fps/playback_cycle
            frame = (cycle%1)*duration*fps
            if GAIT:
                # Camera follows the creature; moving ground is equivalent to forward root travel.
                floor.location.y = cycle*(.32 if clip=='walk' else .44)
            scene.frame_set(int(frame),subframe=frame%1)
            scene.render.filepath = str(frames_dir / f'{index:04}.png')
            bpy.ops.render.render(write_still=True)
            index += 1
    (OUT / 'motion-timeline.json').write_text(json.dumps(timeline,indent=2))
    print('MOTION RENDER PASS',index,'frames at',output_fps,'fps')
