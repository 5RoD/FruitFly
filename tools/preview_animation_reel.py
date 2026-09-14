"""Render the existing Java animation pipeline's recorded surface, not an alternate animation system."""
from pathlib import Path
import json
import math
import sys
import bpy
import numpy as np
from mathutils import Vector

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from repair_creature_weights import read_glb, accessor

OUT=ROOT/'docs/macro-animation'
meta=json.loads((OUT/'reel.json').read_text())
raw=np.memmap(OUT/'reel.f32',dtype='<f4',mode='r').reshape(meta['frames'],meta['vertices']*9+3)
doc,binary,_=read_glb(ROOT/'src/main/resources/assets/flybrain/models/creature-animated.glb')
primitive=doc['meshes'][0]['primitives'][0]
uv=accessor(doc,binary,primitive['attributes']['TEXCOORD_0'])
indices=accessor(doc,binary,primitive['indices']).reshape(-1,3)
wing_ids=set(v for w in doc['extras']['wings'] for v in w['vertices'])
wing_triangles=[triangle.tolist() for triangle in indices if all(int(v) in wing_ids for v in triangle)]
bpy.ops.wm.open_mainfile(filepath=str(ROOT/'art/creature/creature-animations.blend'))
scene=bpy.context.scene
original=next(o for o in scene.objects if o.type=='MESH')
material=original.data.materials[0]
original.hide_render=True
scene.render.engine='BLENDER_EEVEE'
scene.eevee.taa_render_samples=16
scene.render.resolution_x=960;scene.render.resolution_y=480;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG'
if scene.world is None:scene.world=bpy.data.worlds.new('Preview world')
scene.world.color=(.12,.12,.12)
meshes=[];mappings=[]
for slot,triangles in enumerate([indices.tolist(),wing_triangles,wing_triangles]):
    mesh=bpy.data.meshes.new('Java surface '+str(slot))
    mesh.from_pydata(np.zeros((len(uv),3)).tolist(),[],triangles)
    layer=mesh.uv_layers.new()
    for polygon in mesh.polygons:
        polygon.use_smooth=True
        for loop in polygon.loop_indices:
            v=mesh.loops[loop].vertex_index
            layer.data[loop].uv=(float(uv[v,0]),1-float(uv[v,1]))
    mat=material
    if slot:
        mat=material.copy();mat.name='Wing shutter '+str(slot)
        mat.surface_render_method='DITHERED'
        bsdf=next(n for n in mat.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
        bsdf.inputs['Alpha'].default_value=0
    mesh.materials.append(mat)
    for side,angle in [(-1,.55),(1,-1.55)]:
        obj=bpy.data.objects.new('View '+str(side)+' surface '+str(slot),mesh)
        scene.collection.objects.link(obj);obj.location.x=side*1.48;obj.rotation_euler.z=angle
    meshes.append(mesh)
for side,angle in [(-1,.55),(1,-1.55)]:
    bpy.ops.mesh.primitive_plane_add(size=3.15,location=(side*1.48,0,-.006))
    floor=bpy.context.object;floor.rotation_euler.z=angle
    mat=bpy.data.materials.new('Contact grid');mat.use_nodes=True
    nodes=mat.node_tree.nodes
    tex=nodes.new('ShaderNodeTexCoord');mapping=nodes.new('ShaderNodeVectorMath');mapping.operation='ADD'
    checker=nodes.new('ShaderNodeTexChecker');checker.inputs['Scale'].default_value=5
    checker.inputs['Color1'].default_value=(.045,.05,.06,1);checker.inputs['Color2'].default_value=(.09,.10,.12,1)
    mat.node_tree.links.new(tex.outputs['Object'],mapping.inputs[0]);mat.node_tree.links.new(mapping.outputs[0],checker.inputs['Vector'])
    mat.node_tree.links.new(checker.outputs['Color'],nodes.get('Principled BSDF').inputs['Base Color'])
    floor.data.materials.append(mat);mappings.append(mapping)
for location,power,size in [((1,-4,5),900,5),((-4,-1,3),500,4),((0,4,5),1000,4)]:
    bpy.ops.object.light_add(type='AREA',location=location)
    light=bpy.context.object;light.data.energy=power;light.data.size=size
    light.rotation_euler=(Vector((0,0,.8))-light.location).to_track_quat('-Z','Y').to_euler()
bpy.ops.object.camera_add(location=(0,-7,3.7));camera=bpy.context.object
camera.rotation_euler=(Vector((0,0,.85))-camera.location).to_track_quat('-Z','Y').to_euler()
camera.data.type='ORTHO';camera.data.ortho_scale=6.15;scene.camera=camera
def text(body,location,size):
    bpy.ops.object.text_add();obj=bpy.context.object;obj.parent=camera
    obj.location=location;obj.rotation_euler=(0,0,0);obj.data.body=body;obj.data.size=size
    mat=bpy.data.materials.new('Label');mat.use_nodes=True
    nodes=mat.node_tree.nodes;nodes.clear();em=nodes.new('ShaderNodeEmission');out=nodes.new('ShaderNodeOutputMaterial')
    mat.node_tree.links.new(em.outputs[0],out.inputs['Surface']);obj.data.materials.append(mat)
    return obj
label=text('',(-2.9,1.3,-3),.16)
text('JAVA RENDERER PREVIEW  |  ORIGINAL MESH  |  TWO ANGLES',(-2.9,-1.32,-3),.09)
frames=OUT/'reel-frames';frames.mkdir(exist_ok=True)
only='--shots' in sys.argv
scenario=sys.argv[sys.argv.index('--scenario')+1] if '--scenario' in sys.argv else None
selected=set()
for shot in meta['timeline']:
    for fraction in (0,.2,.4,.65,.9):selected.add(shot['startFrame']+int(shot['frames']*fraction))
rendered=0
for i in range(meta['frames']):
    if only and i not in selected:continue
    shot=next(s for s in meta['timeline'] if s['startFrame']<=i<s['startFrame']+s['frames'])
    if scenario and shot['name']!=scenario:continue
    flight=shot['name']=='flight-trajectory'
    camera.data.ortho_scale=9.0 if flight else 6.15
    camera.location.z=4.3 if flight else 3.7
    label.location.y=camera.data.ortho_scale/4-.24 if flight else 1.3
    label.data.body=shot['name'].upper().replace('-',' ')+'   '+f"{shot['rate']:g}x"
    activity,x,z=raw[i,:3]
    data=raw[i,3:].reshape(3,len(uv),3)
    for slot,mesh in enumerate(meshes):
        if slot:
            bsdf=next(n for n in mesh.materials[0].node_tree.nodes if n.type=='BSDF_PRINCIPLED')
            bsdf.inputs['Alpha'].default_value=float(65/255*activity)
        vertices=data[slot][:,[0,2,1]].copy();vertices[:,1]*=-1
        mesh.vertices.foreach_set('co',vertices.reshape(-1));mesh.update()
        for obj in scene.objects:
            if obj.type=='MESH' and obj.data==mesh:obj.hide_render=bool(slot>0 and activity<.02)
    for mapping in mappings:mapping.inputs[1].default_value=(float(x),-float(z),0)
    scene.render.filepath=str(frames/f'{i:05}.png')
    bpy.ops.render.render(write_still=True)
    rendered+=1
    if i==shot['startFrame']:print('SHOT',shot['name'],shot['rate'],i,flush=True)
print('REEL_RENDER_PASS',rendered,flush=True)

