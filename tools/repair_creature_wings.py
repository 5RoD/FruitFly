"""Bind disconnected wing plates rigidly to existing wing bones; preserve every surface byte."""
from pathlib import Path
import hashlib
import json
import numpy as np
from repair_creature_weights import read_glb, accessor, write_glb

ROOT = Path(__file__).resolve().parents[1]


def main():
    source = ROOT / 'art/creature/creature-weight-repaired.glb'
    doc, binary, original = read_glb(source)
    prefix = bytes(binary)
    primitive = doc['meshes'][0]['primitives'][0]
    attributes = primitive['attributes']
    positions = accessor(doc, binary, attributes['POSITION'])
    indices = accessor(doc, binary, primitive['indices']).reshape(-1, 3)
    joints = accessor(doc, binary, attributes['JOINTS_0'])
    weights = accessor(doc, binary, attributes['WEIGHTS_0'])
    names = [doc['nodes'][i]['name'] for i in doc['skins'][0]['joints']]
    # Weld only for component discovery, not to change the exported topology or UV seams.
    _, ids = np.unique(np.round(positions, 5), axis=0, return_inverse=True)
    parents = list(range(int(ids.max())+1))
    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i
    for triangle in ids[indices]:
        root = find(triangle[0])
        for i in triangle[1:]:
            parents[find(i)] = root
    labels = np.array([find(i) for i in ids])
    sides = {-1: [], 1: []}
    components = []
    for label in np.unique(labels):
        selected = np.flatnonzero(labels == label)
        p = positions[selected]
        low, high = p.min(axis=0), p.max(axis=0)
        # The supplied model has many separate dorsal blade-like plates, not two clean wings.
        # Exclude the connected thorax/head, all six leg surfaces, and the mouthparts.
        if high[1] > 1.25 and low[1] > .55 and high[2] < .55 and len(p) < 200:
            side = -1 if p[:, 0].mean() < 0 else 1
            sides[side].extend(selected.tolist())
            components.append({'side': side, 'vertices': len(p), 'bounds': [low.tolist(), high.tolist()]})
    bindings = []
    for side, vertices in sides.items():
        assert len(vertices) > 150, 'Wing selection unexpectedly empty'
        bone = 'Bone_011' if side < 0 else 'Bone_013'
        index = names.index(bone)
        joints[vertices] = [index, index, index, index]
        weights[vertices] = [1, 0, 0, 0]
        bindings.append({'bone': bone, 'side': side, 'pivot': [side*.17, 1.28, .25], 'vertices': vertices})
    toes = {}
    leg_map=json.loads((ROOT/'docs/gait-refinement/leg-map.json').read_text())['legs']
    for name,bone in [('LF','Bone_015'),('RF','Bone_020')]:
        leg=leg_map[name]
        ids=np.array(leg['vertices'])
        ids=ids[positions[ids,1]<.33]
        lower=names.index(leg['bones'][1]); toe=names.index(bone)
        blend=np.clip((.31-positions[ids,1])/.10,0,1)
        joints[ids]=[lower,toe,lower,toe]
        weights[ids]=np.column_stack([1-blend,blend,np.zeros(len(ids)),np.zeros(len(ids))])
        toes[name]={'bone':bone,'wrist':(np.array(leg['contact'])*.7+np.array(leg['knee'])*.3).tolist(),'vertices':ids.tolist()}
    for key, values in [('JOINTS_0', joints), ('WEIGHTS_0', weights)]:
        while len(binary) % 4:
            binary.append(0)
        offset = len(binary)
        binary.extend(values.tobytes())
        doc['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': values.nbytes})
        old = doc['accessors'][attributes[key]]
        doc['accessors'].append({'bufferView': len(doc['bufferViews'])-1, 'componentType': old['componentType'], 'count': len(values), 'type': 'VEC4'})
        attributes[key] = len(doc['accessors'])-1
    doc.setdefault('extras', {})['wings'] = bindings
    doc['extras']['toes']=toes
    target = ROOT / 'art/creature/creature-motion-rigged.glb'
    write_glb(target, doc, binary)
    reopened, data, _ = read_glb(target)
    assert bytes(data[:len(prefix)]) == prefix
    assert np.array_equal(accessor(reopened, data, attributes['POSITION']), positions)
    assert np.allclose(weights.sum(axis=1), 1)
    assert source.read_bytes() == original
    report = {'source_sha256': hashlib.sha256(original).hexdigest(), 'output_sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'bindings': bindings, 'toes':toes, 'components': components, 'changed_fields': ['JOINTS_0', 'WEIGHTS_0'], 'geometry_textures_hierarchy_unchanged': True}
    (ROOT/'docs/macro-animation/wing-bindings.json').write_text(json.dumps(report, indent=2))
    print('WING_BIND_PASS', {side: len(v) for side, v in sides.items()})


if __name__ == '__main__':
    main()
