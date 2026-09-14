"""User-approved leg-weight repair. No vertex, material, bone, hierarchy or bind-matrix edits."""
from pathlib import Path
import hashlib
import json
import struct
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/main/resources/assets/flybrain/models/creature.glb'
OUT = ROOT / 'art/creature/creature-weight-repaired.glb'
# glTF coordinates: +Y up, +Z forward. Virtual anatomical pivots do not edit the supplied bones.
LEGS = {
    'LF': {'bones':['Bone_018','Bone_016'], 'hip':[-.19,1.14,.35], 'knee':[-.365,.84,.365], 'foot':[-.582,0,.48], 'phase':0},
    'LM': {'bones':['Bone_047','Bone_045'], 'hip':[-.21,1.14,.12], 'knee':[-.427,.70,.015], 'foot':[-.646,0,-.06], 'phase':.5},
    'LH': {'bones':['Bone_055','Bone_054'], 'hip':[-.16,.91,-.20], 'knee':[-.29,.55,-.385], 'foot':[-.50,.006,-.61], 'phase':0},
    'RF': {'bones':['Bone_023','Bone_021'], 'hip':[.20,1.14,.35], 'knee':[.375,.84,.365], 'foot':[.582,0,.48], 'phase':.5},
    'RM': {'bones':['Bone_043','Bone_041'], 'hip':[.22,1.14,.12], 'knee':[.437,.70,.015], 'foot':[.646,0,-.06], 'phase':0},
    'RH': {'bones':['Bone_049','Bone_048'], 'hip':[.17,.91,-.20], 'knee':[.30,.55,-.385], 'foot':[.50,.003,-.61], 'phase':.5},
}


def read_glb(path):
    raw = path.read_bytes()
    magic, version, length, size, kind = struct.unpack_from('<5I', raw)
    assert (magic,version,length,kind) == (0x46546C67,2,len(raw),0x4E4F534A)
    return json.loads(raw[20:20+size]), bytearray(raw[28+size:]), raw


def accessor(doc, binary, index):
    a = doc['accessors'][index]
    view = doc['bufferViews'][a['bufferView']]
    dtype = np.dtype({5126:'<f4',5123:'<u2',5121:'u1',5125:'<u4'}[a['componentType']])
    width = {'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}[a['type']]
    return np.ndarray((a['count'],width),dtype=dtype,buffer=binary,
        offset=view.get('byteOffset',0)+a.get('byteOffset',0),
        strides=(view.get('byteStride',dtype.itemsize*width),dtype.itemsize)).copy()


def distance_to_segment(points, a, b):
    a,b = np.array(a),np.array(b)
    t = np.clip((points-a)@(b-a)/np.dot(b-a,b-a),0,1)
    return np.linalg.norm(points-(a+t[:,None]*(b-a)),axis=1)


def write_glb(path, doc, binary):
    doc['buffers'][0]['byteLength'] = len(binary)
    encoded = json.dumps(doc,separators=(',',':')).encode()
    encoded += b' '*((-len(encoded))%4)
    binary += b'\0'*((-len(binary))%4)
    path.write_bytes(struct.pack('<III',0x46546C67,2,28+len(encoded)+len(binary))
        +struct.pack('<II',len(encoded),0x4E4F534A)+encoded
        +struct.pack('<II',len(binary),0x004E4942)+binary)


def main():
    doc,binary,original = read_glb(SOURCE)
    original_doc = json.loads(json.dumps(doc))
    original_binary = bytes(binary)
    attributes = doc['meshes'][0]['primitives'][0]['attributes']
    positions = accessor(doc,binary,attributes['POSITION'])
    joints = accessor(doc,binary,attributes['JOINTS_0'])
    weights = accessor(doc,binary,attributes['WEIGHTS_0'])
    old_joints,old_weights = joints.copy(),weights.copy()
    names = [doc['nodes'][i]['name'] for i in doc['skins'][0]['joints']]
    leg_bones = np.array([14<=int(name[-3:])<=23 or int(name[-3:])>=40 for name in names])
    old_leg_weight = (leg_bones[joints]*weights).sum(axis=1)
    distances = []
    for spec in LEGS.values():
        distances.append(np.minimum(distance_to_segment(positions,spec['hip'],spec['knee']),
                                    distance_to_segment(positions,spec['knee'],spec['foot'])))
    distances = np.array(distances).T
    labels = distances.argmin(axis=1)
    manifest = {}
    for label,(name,spec) in enumerate(LEGS.items()):
        # Existing leg influences and an anatomical tube jointly exclude rear blade/wing geometry.
        selected = (labels==label)&(distances[:,label]<.16)&(old_leg_weight>.35)&(positions[:,1]<spec['hip'][1]-.015)
        ids = np.flatnonzero(selected)
        assert len(ids)>70, (name,len(ids))
        lower = np.clip((spec['knee'][1]+.08-positions[ids,1])/.16,0,1)
        upper_index,lower_index = [names.index(bone) for bone in spec['bones']]
        joints[ids] = [upper_index,lower_index,upper_index,lower_index]
        weights[ids] = np.column_stack([1-lower,lower,np.zeros(len(ids)),np.zeros(len(ids))])
        low = ids[positions[ids,1] < positions[ids,1].min()+.012]
        assert len(low)>0
        contact = positions[low].mean(axis=0)
        contact[1] = positions[low,1].min()
        manifest[name] = dict(spec,vertices=ids.tolist(),contact_vertices=low.tolist(),contact=contact.tolist())
    assert np.allclose(weights.sum(axis=1),1,atol=1e-5)
    assert np.isfinite(weights).all() and (weights>=0).all()
    changed = (np.abs(old_weights-weights).max(axis=1)>1e-6)|(old_joints!=joints).any(axis=1)
    assert 500 < changed.sum() < len(weights)*.65
    for key,values in [('JOINTS_0',joints),('WEIGHTS_0',weights)]:
        while len(binary)%4: binary.append(0)
        start = len(binary); payload = values.tobytes(); binary.extend(payload)
        doc['bufferViews'].append({'buffer':0,'byteOffset':start,'byteLength':len(payload)})
        old = original_doc['accessors'][attributes[key]]
        doc['accessors'].append({'bufferView':len(doc['bufferViews'])-1,'componentType':old['componentType'],'count':len(values),'type':'VEC4'})
        attributes[key] = len(doc['accessors'])-1
    OUT.parent.mkdir(parents=True,exist_ok=True)
    write_glb(OUT,doc,binary)
    reopened,new_binary,_ = read_glb(OUT)
    assert new_binary[:len(original_binary)] == original_binary
    for key in ('nodes','skins','materials','textures','images','scenes'):
        assert reopened[key] == original_doc[key], key
    assert np.array_equal(accessor(reopened,new_binary,attributes['POSITION']),positions)
    assert SOURCE.read_bytes() == original
    report = {'source_sha256':hashlib.sha256(original).hexdigest(),'repaired_sha256':hashlib.sha256(OUT.read_bytes()).hexdigest(),
              'changed_vertices':int(changed.sum()),'vertex_count':len(weights),'bones_preserved':56,
              'mesh_and_original_binary_preserved':True,'user_approved_weight_repair':True,'legs':manifest,
              'status':'CANDIDATE: weight separation verified; gait and rendered deformation still require verification'}
    target = ROOT/'docs/gait-refinement/leg-map.json'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(report,indent=2))
    assert json.loads(target.read_text()) == report
    print('WEIGHT REPAIR CANDIDATE',int(changed.sum()),'vertices; original mesh/56 bones/hierarchy preserved')
    print({name:len(spec['vertices']) for name,spec in manifest.items()})


if __name__ == '__main__': main()
