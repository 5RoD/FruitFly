package dev.flybrain.model;

import com.google.gson.JsonObject;
import java.util.*;
import org.joml.*;

/** glTF LINEAR/CUBICSPLINE translation/quaternion sampling and skinning; entirely independent of game/brain state. */
public final class GlbPose {
    private record Track(int node, boolean rotation, boolean cubic, float[] times, float[] values) {}
    private record Clip(float duration, boolean loop, List<Track> tracks) {}
    private final GlbModel model;
    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final int[] parents, joints;
    private final Vector3f[] restT, restS, translations, previousT;
    private final Quaternionf[] restR, rotations, previousR;
    private final Matrix4f[] world, inverse, skin;
    private final boolean[] computed;
    private final float[] blend = new float[16], scratch = new float[16];
    private final Matrix4f blended = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private final Vector3f vector = new Vector3f();
    private final Quaternionf q = new Quaternionf();
    public final float[] positions, normals;
    public final boolean[] wingVertices;
    public final float[][] wingExposure;
    private final List<JsonObject> wingBindings = new ArrayList<>();
    private int bodyJoint;
    private int feedHead=-1,feedLeft=-1,feedRight=-1;

    public GlbPose(GlbModel model) {
        this.model = model;
        var nodes = model.document.getAsJsonArray("nodes");
        int n = nodes.size();
        parents = new int[n]; Arrays.fill(parents,-1);
        restT = new Vector3f[n]; restS = new Vector3f[n]; restR = new Quaternionf[n];
        translations = new Vector3f[n]; previousT = new Vector3f[n]; rotations = new Quaternionf[n]; previousR = new Quaternionf[n];
        world = new Matrix4f[n]; computed = new boolean[n];
        for (int i=0;i<n;i++) {
            var node = nodes.get(i).getAsJsonObject();
            String nodeName=node.has("name")?node.get("name").getAsString():"";
            if(nodeName.equals("Bone_001"))feedHead=i;
            if(nodeName.equals("Bone_015"))feedLeft=i;
            if(nodeName.equals("Bone_020"))feedRight=i;
            if (node.has("matrix")) throw new IllegalArgumentException("Animated supplied rig requires TRS nodes");
            restT[i] = vec(node,"translation",0); restS[i] = vec(node,"scale",1);
            var r = node.getAsJsonArray("rotation");
            restR[i] = r == null ? new Quaternionf() : new Quaternionf(r.get(0).getAsFloat(),r.get(1).getAsFloat(),r.get(2).getAsFloat(),r.get(3).getAsFloat()).normalize();
            translations[i] = new Vector3f(restT[i]); previousT[i] = new Vector3f(restT[i]);
            rotations[i] = new Quaternionf(restR[i]); previousR[i] = new Quaternionf(restR[i]); world[i] = new Matrix4f();
            if (node.has("children")) for (var child:node.getAsJsonArray("children")) parents[child.getAsInt()] = i;
        }
        var s = model.document.getAsJsonArray("skins").get(0).getAsJsonObject();
        joints = new int[model.jointCount]; inverse = new Matrix4f[joints.length]; skin = new Matrix4f[joints.length];
        float[] ib = model.floats(s.get("inverseBindMatrices").getAsInt(),"MAT4");
        for (int i=0;i<joints.length;i++) {
            joints[i] = s.getAsJsonArray("joints").get(i).getAsInt();
            inverse[i] = new Matrix4f().set(Arrays.copyOfRange(ib,i*16,i*16+16)); skin[i] = new Matrix4f();
            if(nodes.get(joints[i]).getAsJsonObject().get("name").getAsString().equals("Bone_000")) bodyJoint=i;
        }
        wingVertices=new boolean[model.positions.length/3];
        wingExposure=new float[2][model.positions.length];
        var extras=model.document.getAsJsonObject("extras");
        if(extras!=null && extras.has("wings")) for(var element:extras.getAsJsonArray("wings")) {
            var binding=element.getAsJsonObject();
            for(var id:binding.getAsJsonArray("vertices")) {
                int v=id.getAsInt();
                if(v<0 || v>=wingVertices.length || model.weights[v*4]!=1) throw new IllegalArgumentException("Invalid rigid wing vertex");
                wingVertices[v]=true;
            }
            wingBindings.add(binding);
        }
        var animations = model.document.getAsJsonArray("animations");
        if (animations != null) for (var element:animations) {
            var animation = element.getAsJsonObject();
            List<Track> tracks = new ArrayList<>(); Set<String> targets = new HashSet<>(); float duration=0;
            for (var channel:animation.getAsJsonArray("channels")) {
                var c = channel.getAsJsonObject(); var target=c.getAsJsonObject("target");
                int node=target.get("node").getAsInt(); String path=target.get("path").getAsString();
                if (node<0 || node>=n || !(path.equals("rotation") || path.equals("translation")) || !targets.add(node+path))
                    throw new IllegalArgumentException("Unsupported or duplicate animation target");
                var sampler=animation.getAsJsonArray("samplers").get(c.get("sampler").getAsInt()).getAsJsonObject();
                String interpolation=sampler.has("interpolation")?sampler.get("interpolation").getAsString():"LINEAR";
                boolean cubic=interpolation.equals("CUBICSPLINE");
                if(!cubic && !interpolation.equals("LINEAR")) throw new IllegalArgumentException("Unsupported interpolation");
                float[] times=model.floats(sampler.get("input").getAsInt(),"SCALAR");
                boolean rotation=path.equals("rotation"); int width=rotation?4:3;
                float[] values=model.floats(sampler.get("output").getAsInt(),rotation?"VEC4":"VEC3");
                if (times.length<2 || times[0]!=0 || values.length!=times.length*width*(cubic?3:1)) throw new IllegalArgumentException("Invalid animation samples");
                for(float value:values) if(!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite animation sample");
                for (int k=1;k<times.length;k++) if (times[k]<=times[k-1]) throw new IllegalArgumentException("Non-increasing key times");
                if (rotation) for (int k=cubic?4:0;k<values.length;k+=cubic?12:4) {
                    float length=values[k]*values[k]+values[k+1]*values[k+1]+values[k+2]*values[k+2]+values[k+3]*values[k+3];
                    if (java.lang.Math.abs(length-1)>.002) throw new IllegalArgumentException("Non-unit animation quaternion");
                }
                duration=java.lang.Math.max(duration,times[times.length-1]);
                tracks.add(new Track(node,rotation,cubic,times,values));
            }
            String name=animation.get("name").getAsString();
            boolean loop=animation.has("extras") && animation.getAsJsonObject("extras").get("loop").getAsBoolean();
            if (duration<=0 || clips.put(name,new Clip(duration,loop,List.copyOf(tracks)))!=null) throw new IllegalArgumentException("Invalid clip");
        }
        positions=model.restPositions.clone(); normals=model.restNormals.clone();
    }

    private static Vector3f vec(JsonObject node,String key,float fallback) {
        var a=node.getAsJsonArray(key);
        return a==null ? new Vector3f(fallback) : new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());
    }
    public Set<String> clips() { return Collections.unmodifiableSet(clips.keySet()); }
    public float duration(String name) { return clip(name).duration; }
    /** Continuous high-rate wing phase plus two shutter samples; no clip-clock resets or gameplay writes. */
    public void exposeWings(double seconds,float activity) {
        if(!Double.isFinite(seconds) || !Float.isFinite(activity) || activity<0 || activity>1)
            throw new IllegalArgumentException("Invalid wing exposure");
        if(activity<=0) return;
        double phase=2*java.lang.Math.PI*(seconds*197 % 1);
        var transform=new Matrix4f();var point=new Vector3f();
        for(var wing:wingBindings) {
            var pivot=vec(wing,"pivot",0);float side=wing.get("side").getAsFloat();
            for(int sample=0;sample<3;sample++) {
                double a=phase+sample*2*java.lang.Math.PI/3;
                transform.set(skin[bodyJoint]).translate(pivot)
                        .rotateY(-side*activity*(.65f+.5f*(float)java.lang.Math.sin(a)))
                        .rotateZ(side*activity*(.18f+.22f*(float)java.lang.Math.cos(a)))
                        .translate(-pivot.x,-pivot.y,-pivot.z);
                for(var id:wing.getAsJsonArray("vertices")) {
                    int v=id.getAsInt(),p=v*3;
                    transform.transformPosition(point.set(model.positions[p],model.positions[p+1],model.positions[p+2]));
                    float[] target=sample==0?positions:wingExposure[sample-1];
                    target[p]=point.x;target[p+1]=point.y;target[p+2]=point.z;
                    if(sample==0) {
                        skin[model.jointIndices[v*4]].set(transform);
                        transform.transformDirection(point.set(model.normals[p],model.normals[p+1],model.normals[p+2])).normalize();
                        normals[p]=point.x;normals[p+1]=point.y;normals[p+2]=point.z;
                    }
                }
            }
        }
    }
    private Clip clip(String name) {
        Clip clip=clips.get(name); if(clip==null) throw new IllegalArgumentException("Unknown clip: "+name); return clip;
    }
    /** Capture the evaluated skeleton, so interrupted transitions never snap to an old clip. */
    public void beginTransition() {
        var captured=new Matrix4f[world.length];
        for(int i=0;i<world.length;i++) captured[i]=new Matrix4f(world(i));
        // Contact correction changes skin matrices after clip sampling. Capture what was
        // actually drawn, including local compensation for children of corrected bones.
        for(int i=0;i<joints.length;i++) captured[joints[i]].set(skin[i]).mul(new Matrix4f(inverse[i]).invert());
        var local=new Matrix4f();
        for(int i=0;i<translations.length;i++) {
            if(parents[i]<0) local.set(captured[i]);
            else captured[parents[i]].invert(local).mul(captured[i]);
            local.getTranslation(previousT[i]); local.getUnnormalizedRotation(previousR[i]).normalize();
        }
    }
    public void sample(String name,double seconds,float transition) {
        sample(name,seconds,transition,null);
    }
    public void sample(String name,double seconds,float transition,FootPlanting feet) {
        sample(name,seconds,transition,feet,Double.NaN);
    }
    /** Reuse the committed strike pose; articulate only mouth/head and fore-toes while feeding. */
    public void sampleFeeding(double seconds,float transition) {
        if(!Double.isFinite(seconds))throw new IllegalArgumentException("Invalid feeding clock");
        sample("strike",.22,transition,null,seconds);
    }
    private void sample(String name,double seconds,float transition,FootPlanting feet,double feedingTime) {
        if(!Double.isFinite(seconds) || !Float.isFinite(transition) || transition<0 || transition>1) throw new IllegalArgumentException("Invalid animation time/blend");
        Clip clip=clip(name);
        float t=clip.loop ? (float)(seconds-java.lang.Math.floor(seconds/clip.duration)*clip.duration) : (float)java.lang.Math.max(0,java.lang.Math.min(clip.duration,seconds));
        for(int i=0;i<translations.length;i++) { translations[i].set(restT[i]); rotations[i].set(restR[i]); }
        for(Track track:clip.tracks) {
            int a=Arrays.binarySearch(track.times,t); if(a<0) a=java.lang.Math.max(0,-a-2);
            int b=java.lang.Math.min(a+1,track.times.length-1); float f=b==a?0:(t-track.times[a])/(track.times[b]-track.times[a]);
            float[] v=track.values;
            if(track.cubic) {
                int width=track.rotation?4:3;
                float dt=track.times[b]-track.times[a];
                for(int component=0;component<width;component++) {
                    int x=a*width*3+width+component,y=b*width*3+width+component;
                    float f2=f*f,f3=f2*f;
                    scratch[component]=(2*f3-3*f2+1)*v[x]+(f3-2*f2+f)*dt*v[x+width]
                            +(-2*f3+3*f2)*v[y]+(f3-f2)*dt*v[y-width];
                }
                if(track.rotation) rotations[track.node].set(scratch[0],scratch[1],scratch[2],scratch[3]).normalize();
                else translations[track.node].set(scratch[0],scratch[1],scratch[2]);
            } else if(track.rotation) {
                int x=a*4,y=b*4;
                rotations[track.node].set(v[x],v[x+1],v[x+2],v[x+3]).slerp(q.set(v[y],v[y+1],v[y+2],v[y+3]),f).normalize();
            } else {
                int x=a*3,y=b*3;
                translations[track.node].set(v[x]+f*(v[y]-v[x]),v[x+1]+f*(v[y+1]-v[x+1]),v[x+2]+f*(v[y+2]-v[x+2]));
            }
        }
        if(Double.isFinite(feedingTime)) {
            float pulse=(float)java.lang.Math.sin(feedingTime*2*java.lang.Math.PI*3.5);
            if(feedHead>=0)rotations[feedHead].rotateX(.012f*pulse);
            if(feedLeft>=0)rotations[feedLeft].rotateX(.035f*pulse);
            if(feedRight>=0)rotations[feedRight].rotateX(-.035f*pulse);
        }
        if(transition<1) for(int i=0;i<translations.length;i++) {
            translations[i].lerp(previousT[i],1-transition);
            rotations[i].slerp(previousR[i],1-transition).normalize();
        }
        Arrays.fill(computed,false);
        for(int i=0;i<joints.length;i++) skin[i].set(world(joints[i])).mul(inverse[i]);
        if(feet!=null) feet.apply(skin);
        for(int v=0;v<positions.length/3;v++) {
            Arrays.fill(blend,0);
            for(int c=0;c<4;c++) {
                int o=v*4+c; skin[model.jointIndices[o]].get(scratch);
                for(int k=0;k<16;k++) blend[k]+=scratch[k]*model.weights[o];
            }
            blended.set(blend); int p=v*3;
            blended.transformPosition(vector.set(model.positions[p],model.positions[p+1],model.positions[p+2]));
            positions[p]=vector.x;positions[p+1]=vector.y;positions[p+2]=vector.z;
            normal.set(blended).invert().transpose().transform(vector.set(model.normals[p],model.normals[p+1],model.normals[p+2])).normalize();
            normals[p]=vector.x;normals[p+1]=vector.y;normals[p+2]=vector.z;
        }
    }
    private Matrix4f world(int i) {
        if(computed[i]) return world[i];
        world[i].translationRotateScale(translations[i],rotations[i],restS[i]);
        if(parents[i]>=0) world(parents[i]).mul(world[i],world[i]); computed[i]=true; return world[i];
    }
}
