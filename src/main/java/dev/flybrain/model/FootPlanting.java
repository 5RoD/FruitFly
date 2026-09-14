package dev.flybrain.model;

import com.google.gson.JsonObject;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/** Visual contact correction on the existing twelve leg bones. Never moves an entity. */
public final class FootPlanting {
    private static final class Leg {
        final int upper,lower,toe;
        final Vector3f hip,knee,contact;
        final double phase;
        final Vector3d anchor=new Vector3d(), last=new Vector3d(), swingFrom=new Vector3d(), swingTo=new Vector3d(), settleFrom=new Vector3d();
        boolean initialized,wasStance,wasMoving,settling,waitForStance,holdUntilStance;
        double swingStart,settleStart,direction;
        Leg(JsonObject json,List<String> names) {
            upper=names.indexOf(json.getAsJsonArray("bones").get(0).getAsString());
            lower=names.indexOf(json.getAsJsonArray("bones").get(1).getAsString());
            toe=json.has("toeBone")?names.indexOf(json.get("toeBone").getAsString()):-1;
            hip=vec(json,"hip"); knee=vec(json,"knee"); contact=vec(json,"contact");
            phase=json.get("phase").getAsDouble();
            if(upper<0 || lower<0 || !Double.isFinite(phase) || hip.distance(knee)<.001 || knee.distance(contact)<.001)
                throw new IllegalArgumentException("Invalid gait bindings");
        }
    }
    private final List<Leg> legs=new ArrayList<>();
    private final int root;
    private final Matrix4d modelToWorld=new Matrix4d(), previousWorld=new Matrix4d(), worldToModel=new Matrix4d();
    private double phase,previousPhase,now,lastTime=Double.NaN,duty=.62,direction=1;
    private boolean enabled,moving,locomotion;
    private double reachError;
    private int contactEvents;
    public int contactEvents(){return contactEvents;}
    private float bodyPitch,bodyRoll;
    private boolean idle;
    private java.util.function.ToDoubleFunction<Vector3d> groundProbe=point->point.y;
    private java.util.function.UnaryOperator<Vector3d> surfaceProbe;
    private final Vector3d up=new Vector3d(0,1,0);
    public void surfaceProbe(java.util.function.UnaryOperator<Vector3d> probe) {surfaceProbe=java.util.Objects.requireNonNull(probe);}
    public void groundProbe(java.util.function.ToDoubleFunction<Vector3d> probe) {groundProbe=java.util.Objects.requireNonNull(probe);}
    private void support(Vector3d point) {
        if(surfaceProbe!=null) {
            var contact=surfaceProbe.apply(new Vector3d(point));
            if(contact!=null && contact.isFinite() && contact.distance(point)<.6)point.set(contact);
            return;
        }
        double height=groundProbe.applyAsDouble(new Vector3d(point));
        if(Double.isFinite(height) && java.lang.Math.abs(height-point.y)<.6) point.y=height;
    }
    public void attitude(float pitch,float roll) {bodyPitch=pitch;bodyRoll=roll;}
    public void reset() {lastTime=Double.NaN;}

    public FootPlanting(GlbModel model) {
        var names=new ArrayList<String>();
        for(var node:model.document.getAsJsonArray("skins").get(0).getAsJsonObject().getAsJsonArray("joints"))
            names.add(model.document.getAsJsonArray("nodes").get(node.getAsInt()).getAsJsonObject().get("name").getAsString());
        root=names.indexOf("Bone_000");
        var extras=model.document.getAsJsonObject("extras");
        if(extras!=null && extras.has("gait")) {
            if(root<0 || extras.getAsJsonObject("gait").size()!=6) throw new IllegalArgumentException("Expected six-leg rig");
            for(var entry:extras.getAsJsonObject("gait").entrySet()) legs.add(new Leg(entry.getValue().getAsJsonObject(),names));
        }
    }
    private static Vector3f vec(JsonObject json,String key) {
        var a=json.getAsJsonArray(key);
        if(a.size()!=3) throw new IllegalArgumentException("Expected gait vector");
        var v=new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());
        if(!v.isFinite()) throw new IllegalArgumentException("Nonfinite gait vector");
        return v;
    }
    public void update(Matrix4d transform,double cycle,double seconds,String clip,boolean grounded) {
        if(!transform.isFinite() || java.lang.Math.abs(transform.determinant())<1e-9 || !Double.isFinite(cycle) || !Double.isFinite(seconds))
            throw new IllegalArgumentException("Invalid foot-plant frame");
        boolean reset=Double.isNaN(lastTime) || seconds<lastTime || seconds-lastTime>.5
                || transform.getTranslation(new Vector3d()).distance(modelToWorld.getTranslation(new Vector3d()))>2;
        if(reset) for(var leg:legs) {leg.initialized=false;leg.holdUntilStance=false;}
        previousPhase=phase; phase=cycle; now=seconds; lastTime=seconds;
        if(java.lang.Math.abs(phase-previousPhase)>1e-9) direction=java.lang.Math.signum(phase-previousPhase);
        locomotion=clip.equals("walk") || clip.equals("run");
        boolean action=clip.equals("hurt") || clip.equals("strike") || clip.equals("land");
        moving=locomotion || (action && java.lang.Math.abs(phase-previousPhase)>1e-9);
        idle=clip.equals("idle") || clip.equals("groom");
        enabled=grounded && (moving || idle || clip.equals("hurt") || clip.equals("strike") || clip.equals("land"));
        if(locomotion)duty=clip.equals("run")?.56:.62;
        previousWorld.set(transform);
        if(!reset) previousWorld.set(modelToWorld);
        modelToWorld.set(transform); transform.invert(worldToModel);
        transform.transformDirection(new Vector3d(0,1,0),up).normalize();
    }
    public double reachError() { return reachError; }
    private static double smooth(double s) {
        s=java.lang.Math.clamp(s,0,1);
        return s*s*s*(10+s*(-15+6*s));
    }
    void apply(Matrix4f[] skin) {
        reachError=0;
        if(root>=0 && (bodyPitch!=0 || bodyRoll!=0)) {
            var attitude=new Matrix4f().translation(0,.95f,0).rotateZ(bodyRoll).rotateX(bodyPitch).translate(0,-.95f,0);
            for(var matrix:skin) attitude.mul(matrix,matrix);
        }
        if(!enabled) { for(var leg:legs) {leg.initialized=false;leg.holdUntilStance=false;} return; }
        for(var leg:legs) {
            Vector3d authored=modelToWorld.transformPosition(new Vector3d(skin[leg.toe>=0?leg.toe:leg.lower].transformPosition(leg.contact,new Vector3f())));
            var homePlane=modelToWorld.transformPosition(new Vector3d(leg.contact));
            // Do not erase intentional leg lifts, grooming or an attack with an all-feet idle lock.
            if(!locomotion && worldToModel.transformPosition(new Vector3d(authored)).y>leg.contact.y+.012) {
                leg.initialized=false;leg.holdUntilStance=false;continue;
            }
            double p=phase+leg.phase; p-=java.lang.Math.floor(p);
            boolean stance=!moving || p<duty;
            if(leg.initialized && moving && stance && !leg.wasStance)contactEvents++;
            if(leg.initialized && moving && !leg.wasMoving && !stance) leg.holdUntilStance=true;
            if(leg.holdUntilStance) {
                if(stance) leg.holdUntilStance=false;
                else stance=true; // Restart on the next complete stroke, not the tail of a frozen swing.
            }
            double swing=direction>0?(p-duty)/(1-duty):(1-p)/(1-duty);
            if(!leg.initialized) {
                leg.anchor.set(authored); leg.last.set(authored);
                if(stance) {support(leg.anchor);leg.last.set(leg.anchor);}
                leg.initialized=true; leg.wasStance=stance; leg.wasMoving=moving; leg.swingStart=0; leg.settling=false; leg.direction=direction;
                leg.waitForStance=!stance;
            }
            // A newly visible entity may already be at the end of a swing. Finish that
            // authored stroke instead of planning an entire new step in its final millisecond.
            if(leg.waitForStance) {
                leg.last.set(authored);
                if(!stance) continue;
                leg.anchor.set(authored); support(leg.anchor); leg.wasStance=true; leg.waitForStance=false;
            }
            Vector3d target;
            if(!moving) {
                if(leg.wasMoving && !leg.wasStance) {
                    leg.settleFrom.set(leg.last); leg.anchor.set(leg.last);
                    // Start from entity height, then query the optional renderer terrain probe.
                    leg.anchor.fma(-new Vector3d(leg.anchor).sub(homePlane).dot(up),up);
                    support(leg.anchor);
                    leg.settleStart=now; leg.settling=true;
                }
                target=leg.settling ? new Vector3d(leg.settleFrom).lerp(leg.anchor,smooth((now-leg.settleStart)/.12)) : new Vector3d(leg.anchor);
                if(now-leg.settleStart>=.12) leg.settling=false;
            } else if(stance) {
                if(!leg.wasStance) leg.anchor.set(leg.swingTo);
                target=new Vector3d(leg.anchor);
            } else {
                if(leg.wasStance || leg.direction!=direction) {
                    leg.swingFrom.set(leg.last); leg.swingStart=swing;
                    Vector3d home=modelToWorld.transformPosition(new Vector3d(leg.contact));
                    Vector3d travel=new Vector3d(home).sub(previousWorld.transformPosition(new Vector3d(leg.contact)));
                    double remaining=(1-swing)*(1-duty)+duty*.5;
                    double phaseStep=java.lang.Math.abs(phase-previousPhase);
                    if(phaseStep<.000001) travel.zero(); // First restart frame has no measured cycle velocity yet.
                    else travel.mul(remaining/phaseStep);
                    double limit=.25*modelToWorld.getScale(new Vector3d()).x;
                    if(travel.length()>limit) travel.normalize(limit);
                    // A fixed world landing gives zero touchdown velocity, including while turning.
                    leg.swingTo.set(home).add(travel);
                    leg.swingTo.fma(-new Vector3d(leg.swingTo).sub(home).dot(up),up);
                    support(leg.swingTo);
                }
                double s=java.lang.Math.clamp((swing-leg.swingStart)/java.lang.Math.max(1e-6,1-leg.swingStart),0,1);
                target=new Vector3d(leg.swingFrom).lerp(leg.swingTo,smooth(s));
                double lift=duty>.6?.045:.068;
                target.fma(64*lift*modelToWorld.getScale(new Vector3d()).y*s*s*s*(1-s)*(1-s)*(1-s),up);
            }
            Vector3d localDouble=worldToModel.transformPosition(target,new Vector3d());
            Vector3f local=new Vector3f((float)localDouble.x,(float)localDouble.y,(float)localDouble.z);
            Vector3f hip=skin[root].transformPosition(leg.hip,new Vector3f());
            float a=leg.hip.distance(leg.knee), b=leg.knee.distance(leg.contact);
            Vector3f axis=new Vector3f(local).sub(hip);
            float distance=axis.length(), bounded=java.lang.Math.clamp(distance,java.lang.Math.abs(a-b)+.00001f,a+b-.00001f);
            reachError=java.lang.Math.max(reachError,java.lang.Math.abs(distance-bounded));
            axis.div(java.lang.Math.max(distance,.000001f));
            local.set(hip).fma(bounded,axis);
            Vector3f pole=new Vector3f(leg.contact).sub(leg.hip).cross(new Vector3f(leg.knee).sub(leg.hip)).cross(axis);
            if(pole.lengthSquared()<1e-12) { leg.initialized=false; continue; }
            pole.normalize();
            float along=(a*a-b*b+bounded*bounded)/(2*bounded);
            float height=(float)java.lang.Math.sqrt(java.lang.Math.max(0,a*a-along*along));
            Vector3f knee=new Vector3f(hip).fma(along,axis).fma(height,pole);
            var oldLower=leg.toe>=0?new Matrix4f(skin[leg.lower]):null;
            skin[leg.upper].translation(hip).rotate(new Quaternionf().rotationTo(new Vector3f(leg.knee).sub(leg.hip),new Vector3f(knee).sub(hip)))
                    .translate(-leg.hip.x,-leg.hip.y,-leg.hip.z);
            skin[leg.lower].translation(knee).rotate(new Quaternionf().rotationTo(new Vector3f(leg.contact).sub(leg.knee),new Vector3f(local).sub(knee)))
                    .translate(-leg.knee.x,-leg.knee.y,-leg.knee.z);
            if(leg.toe>=0) new Matrix4f(skin[leg.lower]).mul(oldLower.invert()).mul(skin[leg.toe],skin[leg.toe]);
            leg.last.set(modelToWorld.transformPosition(new Vector3d(local)));
            leg.wasStance=stance; leg.wasMoving=moving; leg.direction=direction;
        }
    }
}
