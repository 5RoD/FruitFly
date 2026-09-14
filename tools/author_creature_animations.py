"""Author/bake in-place clips on the supplied bones; append glTF tracks without rewriting mesh data."""
import bpy
import json
import math
import struct
import hashlib
from pathlib import Path
from mathutils import Matrix, Vector, Quaternion

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'art/creature/creature-motion-rigged.glb'
OUT = ROOT / 'src/main/resources/assets/flybrain/models/creature-animated.glb'
ART = ROOT / 'art/creature'
FPS = 120
CLIPS = {'idle': (12.0, True), 'walk': (.65, True), 'run': (.325, True),
         'takeoff': (.3, False), 'air': (.8, True), 'land': (.45, False),
         'strike': (.6, False), 'hurt': (.45, False), 'death': (.9, False), 'groom': (3.2, False)}


def smooth(x):
    x = max(0, min(1, x))
    return x*x*x*(10+x*(-15+6*x))


def envelope(t, start, attack, hold, release):
    return smooth((t-start)/attack)*(1-smooth((t-start-attack-hold)/release))


def keyed(t, keys):
    for (a, x), (b, y) in zip(keys, keys[1:]):
        if t <= b:
            return x+(y-x)*smooth((t-a)/(b-a))
    return keys[-1][1]


def around(pivot, pitch=0, yaw=0, roll=0):
    p = Vector(pivot)
    return Matrix.Translation(p) @ Matrix.Rotation(yaw,4,'Y') @ Matrix.Rotation(roll,4,'Z') @ Matrix.Rotation(pitch,4,'X') @ Matrix.Translation(-p)


def foot_path(phase, length, duty, lift):
    """C2 liftoff/contact; stance cancels length * phase of forward body travel."""
    if phase < duty:
        return length*(duty/2-phase), 0.0
    s = (phase-duty)/(1-duty)
    stride = length*duty
    velocity = -length*(1-duty)
    quintic = 10*s**3-15*s**4+6*s**5
    return -stride/2+velocity*s+(stride-velocity)*quintic, 64*lift*s**3*(1-s)**3


def solve_knee(h, k, c, hip, target):
    upper,lower = (k-h).length,(c-k).length
    direction = target-hip
    distance = direction.length
    residual = max(0,distance-upper-lower,abs(upper-lower)-distance)
    direction.normalize()
    # Stable rest-plane pole; no iterative solver, stretch, target discontinuity or knee flip.
    # Project the fixed anatomical bend plane, not the upper-leg vector: the latter
    # flips sides when a nearly straight auto-rig leg crosses its rest direction.
    normal = (c-h).cross(k-h)
    pole = normal.cross(direction)
    assert pole.length>1e-5, 'Degenerate knee pole'
    pole.normalize()
    along = (upper*upper-lower*lower+distance*distance)/(2*distance)
    height = math.sqrt(max(0,upper*upper-along*along))
    return hip+direction*along+pole*height,residual


def main():
    original = SOURCE.read_bytes()
    size = struct.unpack_from('<I', original, 12)[0]
    doc = json.loads(original[20:20+size])
    binary = bytearray(original[28+size:])
    old_binary = bytes(binary)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(SOURCE))
    rig = next(o for o in bpy.context.scene.objects if o.type == 'ARMATURE')
    scene = bpy.context.scene
    scene.render.fps = FPS
    rig.animation_data_create()
    legs = json.loads((ROOT/'docs/gait-refinement/leg-map.json').read_text())['legs']
    doc.setdefault('extras', {})['gait'] = {
        name: {key: spec[key] for key in ('bones','hip','knee','contact','phase')} for name,spec in legs.items()}
    for name,spec in doc['extras']['gait'].items():
        spec['phase'] += .015 if name.endswith('M') else -.015 if name.endswith('H') else 0
        if name in doc['extras'].get('toes',{}): spec['toeBone']=doc['extras']['toes'][name]['bone']
    parents = {c:i for i,n in enumerate(doc['nodes']) for c in n.get('children',[])}
    rest = {}
    def world(i):
        if i in rest: return rest[i]
        n = doc['nodes'][i]
        x,y,z,w = n.get('rotation',[0,0,0,1])
        local = Matrix.LocRotScale(Vector(n.get('translation',[0,0,0])),Quaternion((w,x,y,z)),Vector(n.get('scale',[1,1,1])))
        rest[i] = world(parents[i]) @ local if i in parents else local
        return rest[i]
    for i in range(len(doc['nodes'])): world(i)
    convert = Matrix(((1,0,0,0),(0,0,1,0),(0,-1,0,0),(0,0,0,1)))
    joints = doc['skins'][0]['joints']
    mapped = {i:rig.pose.bones[doc['nodes'][i]['name']] for i in joints}
    doc['animations'] = []
    evidence = {}
    ART.mkdir(parents=True,exist_ok=True)
    for clip,(duration,loop) in CLIPS.items():
        count = round(duration*FPS)
        frames = []
        contacts = []
        for frame in range(count+1):
            t = frame/FPS; u = frame/count; cycle = 2*math.pi*u
            # All targets use glTF world axes (+Y up, +Z forward). No object/root travel.
            moving = clip in ('walk','run')
            bob = -.095
            sway = surge = 0
            pitch = roll = 0
            groom = envelope(t,.3,.35,1.55,.65) if clip=='groom' else 0
            if moving:
                # Low-amplitude support transfer, not a floating sinusoidal whole-mesh hop.
                bob += (.006 if clip=='walk' else .009)*math.cos(cycle*2)
                sway = .009*math.sin(cycle)
                roll = .018*math.sin(cycle)
                pitch = -.022+ .009*math.sin(cycle*2+.2)
            elif clip in ('idle','groom'):
                surge = -.035*groom
                pitch = .025*groom
            elif clip == 'takeoff':
                # Visual leg extension accompanies an already observed lift-off, no invented root travel.
                bob += keyed(u,[(0,0),(.18,-.035),(.48,.028),(1,.015)])
                pitch = -.065*smooth(u)
            elif clip == 'air':
                bob += .015
                pitch = -.065
            elif clip == 'land':
                bob += keyed(u,[(0,.015),(.23,-.07),(.55,-.015),(1,0)])
                pitch = keyed(u,[(0,-.065),(.23,.075),(.62,-.015),(1,0)])
            elif clip == 'strike':
                surge = keyed(u,[(0,0),(.22,-.045),(.39,.09),(.52,.055),(1,0)])
                pitch = keyed(u,[(0,0),(.22,.07),(.39,-.14),(.58,-.06),(1,0)])
                bob -= .02*envelope(u,0,.2,.3,.5)
            elif clip == 'hurt':
                surge = keyed(u,[(0,0),(.14,-.05),(.4,-.02),(1,0)])
                roll = keyed(u,[(0,0),(.13,-.09),(.45,.025),(1,0)])
                bob -= .025*envelope(u,0,.15,.05,.8)
            elif clip == 'death':
                collapse = keyed(u,[(0,0),(.13,.07),(.48,.86),(.67,1),(.78,.985),(.9,1),(1,1)])
                bob -= .425*collapse
                roll = .34*collapse
                pitch = .12*collapse
            pivot = Vector((0,.95,0))
            body = (Matrix.Translation(Vector((sway,bob,surge))) @ Matrix.Translation(pivot)
                    @ Matrix.Rotation(roll,4,'Z') @ Matrix.Rotation(pitch,4,'X') @ Matrix.Translation(-pivot))
            deform = {i:body.copy() for i in joints}
            # Anatomical head pivot; compensate the peculiar auto-rig hierarchy below.
            look = (envelope(t,1.1,.13,.55,.25)*.07-envelope(t,3.2,.09,.22,.2)*.045) if clip=='idle' else 0
            nod = groom*(.035+.018*math.sin(2*math.pi*4*t))
            if clip=='strike': nod -= .07*envelope(u,.2,.18,.05,.5)
            if clip=='death': nod += .14*smooth(u/.6)
            head = body @ around((0,1.06,.48),pitch=nod,yaw=look)
            for i in joints:
                if doc['nodes'][i]['name'] in {f'Bone_{j:03}' for j in range(1,8)}:
                    deform[i] = head
                if clip=='death' and doc['nodes'][i]['name'] in {f'Bone_{j:03}' for j in range(2,8)}:
                    deform[i] = body @ around((0,.93,.65),pitch=-1.0*smooth(u/.55))
            deploy = 0
            if clip=='takeoff': deploy=smooth(u/.4)
            elif clip=='air': deploy=1
            elif clip=='land': deploy=1-smooth(u/.65)
            # Authored 8.75 Hz is an inspectable temporal proxy. Runtime exposure uses a continuous 197 Hz phase.
            stroke = math.sin(2*math.pi*8.75*t)
            for wing in doc['extras']['wings']:
                side=wing['side']
                index=next(i for i in joints if doc['nodes'][i]['name']==wing['bone'])
                folded = .25*smooth(u/.7) if clip=='death' else 0
                deform[index] = body @ around(wing['pivot'],yaw=-side*deploy*(.65+.5*stroke),roll=side*(folded+deploy*(.18+.22*math.cos(2*math.pi*8.75*t))))
            contact_frame = {}
            for name,spec in legs.items():
                h,k,c = (Vector(spec[key]) for key in ('hip','knee','contact'))
                target = c.copy()
                # Small phase offsets within each tripod, not independent/random leg clocks.
                phase_offset = {'LF':0,'LM':.015,'LH':-.015,'RF':0,'RM':.015,'RH':-.015}[name]
                p = (u+spec['phase']+phase_offset)%1
                stance = True
                if moving:
                    length,duty,lift = (.32,.62,.045) if clip=='walk' else (.44,.56,.068)
                    dz,dy = foot_path(p,length,duty,lift)
                    target.z += dz
                    target.y += dy
                    stance = p < duty
                    if not stance:
                        target.x += (.004 if name.startswith('R') else -.004)*math.sin(math.pi*(p-duty)/(1-duty))**4
                if name.endswith('H'): target.z += .025
                if clip in ('takeoff','air','land'):
                    tuck = smooth((u-.2)/.65) if clip=='takeoff' else 1 if clip=='air' else 1-smooth(u/.22)
                    target.y += (.28 if name.endswith('F') else .22)*tuck
                    target.x *= 1-.18*tuck
                    target.z -= .04*tuck
                    stance = False
                elif clip == 'strike' and name.endswith('F'):
                    pulse = envelope(u,.02,.2,.2,.56)
                    target.y += .30*pulse
                    target.z += keyed(u,[(0,0),(.22,-.03),(.39,.22),(.52,.16),(1,0)])
                    stance = pulse < .001
                elif clip == 'groom' and name.endswith('F'):
                    side=-1 if name.startswith('L') else 1
                    stroke=math.sin(2*math.pi*4.5*t+(0 if side<0 else .45))
                    face=Vector((side*(.09+.025*stroke),.77+.05*stroke,.75+.025*stroke))
                    target=target.lerp(face,groom)
                    stance = groom < .001
                elif clip == 'idle' and name=='RH':
                    adjust=envelope(t,9.0,.12,.08,.18)
                    target.y += .05*adjust
                    target.z += .02*adjust
                    stance=adjust<.001
                elif clip == 'death':
                    target.x += (-1 if name.startswith('L') else 1)*.20*smooth(u/.65)
                    target.z += (.09 if name.endswith('F') else -.06)*smooth(u/.65)
                hip = body @ h
                knee,residual = solve_knee(h,k,c,hip,target) if clip!='groom' or not name.endswith('F') else (k,0)
                toe=doc['extras'].get('toes',{}).get(name)
                wrist=None
                if toe and clip=='groom':
                    w=Vector(toe['wrist'])
                    # Fold the existing distal joint, rather than shortening a long two-link leg to reach the face.
                    rest_knee,_=solve_knee(h,k,c,hip,c)
                    rest_lower=(c-k).rotation_difference(c-rest_knee)
                    rest_wrist=rest_knee+rest_lower@(w-k)
                    side=-1 if name.startswith('L') else 1
                    direction=Vector((-side*.14,.23,.08)).normalized()
                    wrist=rest_wrist.lerp(target-direction*(c-w).length,groom)
                    knee,residual=solve_knee(h,k,w,hip,wrist)
                assert residual < 1e-5, (clip,frame,name,'unreachable',residual)
                upper = Matrix.Translation(hip) @ (k-h).rotation_difference(knee-hip).to_matrix().to_4x4() @ Matrix.Translation(-h)
                lower = Matrix.Translation(knee) @ ((Vector(toe['wrist']) if wrist else c)-k).rotation_difference((wrist if wrist else target)-knee).to_matrix().to_4x4() @ Matrix.Translation(-k)
                for bone_name,matrix in zip(spec['bones'],(upper,lower)):
                    index = next(i for i in joints if doc['nodes'][i]['name']==bone_name)
                    deform[index] = matrix
                if toe:
                    index=next(i for i in joints if doc['nodes'][i]['name']==toe['bone'])
                    if wrist:
                        w=Vector(toe['wrist'])
                        deform[index]=Matrix.Translation(wrist)@(c-w).rotation_difference(target-wrist).to_matrix().to_4x4()@Matrix.Translation(-w)
                    else: deform[index]=lower.copy()
                contact_frame[name] = {'target':list(target),'stance':stance,'residual':residual}
            # Original rest skeleton/hierarchy stays untouched. Local compensation lets the
            # existing bones act around anatomical pivots despite the auto-rig's poor placement.
            evaluated = {i:deform[i] @ rest[i] for i in joints}
            for i,bone in mapped.items():
                bone.matrix_basis.identity()
                bone.rotation_mode = 'QUATERNION'
            pose = {bone.name:rig.matrix_world.inverted() @ convert.inverted() @ deform[i]
                    @ convert @ rig.matrix_world @ bone.bone.matrix_local for i,bone in mapped.items()}
            transforms = {}
            for i,b in mapped.items():
                parent = parents.get(i)
                local = (evaluated.get(parent,rest.get(parent,Matrix.Identity(4)))).inverted() @ evaluated[i]
                loc,rot,scale = local.decompose()
                transforms[i] = (list(loc),[rot.x,rot.y,rot.z,rot.w])
            frames.append((transforms,pose))
            contacts.append(contact_frame)
        # Store the exact evaluated poses as editable keyframes, without baking helper geometry into the creature.
        for b in rig.pose.bones:
            for c in b.constraints: c.mute = True
        action = bpy.data.actions.new(clip)
        action.use_fake_user = True
        rig.animation_data.action = action
        previous_quaternions = {}
        for frame,(_,pose) in enumerate(frames):
            for b in rig.pose.bones:
                if b.parent:
                    b.matrix_basis = b.bone.convert_local_to_pose(pose[b.name], b.bone.matrix_local,
                        parent_matrix=pose[b.parent.name], parent_matrix_local=b.parent.bone.matrix_local, invert=True)
                else:
                    b.matrix_basis = b.bone.matrix_local.inverted() @ pose[b.name]
            for b in rig.pose.bones:
                if frame and b.rotation_quaternion.dot(previous_quaternions[b.name]) < 0:
                    b.rotation_quaternion.negate()
                b.keyframe_insert('location',frame=frame)
                b.keyframe_insert('rotation_quaternion',frame=frame)
                b.keyframe_insert('scale',frame=frame)
                previous_quaternions[b.name] = b.rotation_quaternion.copy()
        for layer in action.layers:
            for strip in layer.strips:
                for bag in strip.channelbags:
                    for curve in bag.fcurves:
                        for key in curve.keyframe_points: key.interpolation = 'LINEAR'
        rig.animation_data.action = None
        for b in rig.pose.bones:
            for c in b.constraints: c.mute = False
        def accessor(values,kind):
            flat = [v for row in values for v in row]
            while len(binary)%4: binary.append(0)
            offset = len(binary)
            binary.extend(struct.pack('<'+'f'*len(flat),*flat))
            view = len(doc['bufferViews'])
            doc['bufferViews'].append({'buffer':0,'byteOffset':offset,'byteLength':len(flat)*4})
            a = {'bufferView':view,'componentType':5126,'count':len(values),'type':kind}
            if kind=='SCALAR': a.update(min=[min(flat)],max=[max(flat)])
            doc['accessors'].append(a)
            return len(doc['accessors'])-1
        times = accessor([[frame/FPS] for frame in range(count+1)],'SCALAR')
        animation = dict(name=clip,samplers=[],channels=[],extras={'loop':loop,'duration':duration})
        for i in joints:
            for component,path,kind in [(0,'translation','VEC3'),(1,'rotation','VEC4')]:
                values=[f[0][i][component] for f in frames]
                if loop: values[-1]=values[0].copy()
                if path=='rotation':
                    for key in range(1,len(values)):
                        if sum(a*b for a,b in zip(values[key-1],values[key]))<0:
                            values[key]=[-v for v in values[key]]
                cubic=[]
                for key,value in enumerate(values):
                    if loop:
                        previous=values[key-1 if key>0 else len(values)-2]
                        following=values[key+1 if key<len(values)-1 else 1]
                        tangent=[(b-a)*FPS/2 for a,b in zip(previous,following)]
                    elif key in (0,len(values)-1): tangent=[0]*len(value)
                    else: tangent=[(b-a)*FPS/2 for a,b in zip(values[key-1],values[key+1])]
                    cubic.extend([tangent,value,tangent])
                output = accessor(cubic,kind)
                sampler = len(animation['samplers'])
                animation['samplers'].append({'input':times,'output':output,'interpolation':'CUBICSPLINE'})
                animation['channels'].append({'sampler':sampler,'target':{'node':i,'path':path}})
        doc['animations'].append(animation)
        evidence[clip]={'duration':duration,'frames':count+1,'loop':loop,'cycleDistance':(.32 if clip=='walk' else .44 if clip=='run' else 0),'maxIkResidual':max(c['residual'] for f in contacts for c in f.values())}
        if clip in ('walk','run'):
            (ROOT/'docs/gait-refinement'/f'{clip}-contacts.json').write_text(json.dumps(contacts,indent=2))
    for b in rig.pose.bones:
        for c in list(b.constraints): b.constraints.remove(c)
    rig.animation_data.action = bpy.data.actions['idle']
    scene.frame_start=0; scene.frame_end=round(CLIPS['idle'][0]*FPS); scene.frame_set(0)
    bpy.ops.wm.save_as_mainfile(filepath=str(ART/'creature-animations.blend'))
    doc['buffers'][0]['byteLength']=len(binary)
    encoded=json.dumps(doc,separators=(',',':')).encode()
    encoded += b' '*((-len(encoded))%4)
    binary += b'\0'*((-len(binary))%4)
    result=struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary
    OUT.write_bytes(result)
    assert bytes(binary[:len(old_binary)])==old_binary
    assert SOURCE.read_bytes()==original
    evidence['source_sha256']=hashlib.sha256((ROOT/'src/main/resources/assets/flybrain/models/creature.glb').read_bytes()).hexdigest()
    evidence['approved_repair_sha256']=hashlib.sha256(original).hexdigest()
    evidence['original_binary_preserved']=True
    (ROOT/'docs/animation-evidence/authoring.json').write_text(json.dumps(evidence,indent=2))
    print('AUTHORED',OUT,len(result),evidence)


if __name__=='__main__': main()

