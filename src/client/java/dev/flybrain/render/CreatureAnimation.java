package dev.flybrain.render;

import dev.flybrain.entity.CreatureEntity;
import dev.flybrain.model.GlbModel;
import dev.flybrain.model.GlbPose;

/** Visual-only state. Observes actual entity motion/events; never commands movement or brain activity. */
final class CreatureAnimation {
    final GlbPose pose;
    private final dev.flybrain.model.GaitClock gait=new dev.flybrain.model.GaitClock();
    private final dev.flybrain.model.FootPlanting feet;
    private String clip="idle";
    private int age=-1, lastHurt;
    private double x,y,z,heading,started,blendStart;
    private boolean grounded=true, swinging, wasFeeding;
    private final org.joml.Quaterniond surfaceRotation=new org.joml.Quaterniond();
    private double surfaceTime=Double.NaN;
    private final org.joml.Vector3d tangent=new org.joml.Vector3d(0,0,1);
    private boolean wasAttached;
    private double lastFrame=Double.NaN,previousSpeed;
    private float lean,bank,targetLean,targetBank,wingAmount;
    private final java.util.Map<Long,Double> groundCache=new java.util.HashMap<>();
    private double nextGroom=Double.NaN;
    float wingAmount() {return wingAmount;}

    CreatureAnimation(GlbModel model) {
        pose=new GlbPose(model); feet=new dev.flybrain.model.FootPlanting(model);
        pose.sample("idle",0,1);pose.beginTransition();
    }

    org.joml.Quaterniond orientation(CreatureEntity entity,float yaw,float partial) {
        double seconds=dev.flybrain.model.GaitClock.seconds(entity.visualAge()+entity.deathTime,entity.animationPaused()?0:partial);
        boolean first=Double.isNaN(surfaceTime);
        double dt=first?1:Math.clamp(seconds-surfaceTime,0,.1);surfaceTime=seconds;
        boolean attached=entity.visualMode().equals("wall") || entity.visualMode().equals("ceiling");
        var n=attached?entity.surfaceNormal():new net.minecraft.util.math.Vec3d(0,1,0);
        double heading=Math.toRadians(yaw-dev.flybrain.FlyBrain.config.modelYawDegrees);
        var forward=new org.joml.Vector3d(-Math.sin(heading),0,Math.cos(heading));
        if(attached) {
            var motion=new org.joml.Vector3d(entity.getX()-entity.prevX,entity.getY()-entity.prevY,entity.getZ()-entity.prevZ);
            if(motion.lengthSquared()>.000001)tangent.set(motion);
            else if(!wasAttached)tangent.set(forward);
            forward.set(tangent);
        }
        wasAttached=attached;
        var target=dev.flybrain.model.SurfaceFrame.rotation(new org.joml.Vector3d(n.x,n.y,n.z),forward);
        if(first)surfaceRotation.set(target);
        else if(!entity.animationPaused())surfaceRotation.slerp(target,-Math.expm1(-dt/.12)).normalize();
        return surfaceRotation;
    }
    GlbPose update(CreatureEntity entity,float partial,org.joml.Matrix4d modelToWorld) {
        if(entity.animationPaused() && entity.isAlive() && age>=0)return pose;
        int clockAge=entity.visualAge()+entity.deathTime;
        boolean contact=entity.isOnGround() || entity.visualMode().equals("wall") || entity.visualMode().equals("ceiling");
        double now=dev.flybrain.model.GaitClock.seconds(clockAge,entity.animationPaused()?0:partial);
        double dt=Double.isNaN(lastFrame)?0:Math.clamp(now-lastFrame,0,.1);lastFrame=now;
        boolean first=age<0, resumed=!first && entity.age-age>1;
        if(first) {
            x=entity.getX();y=entity.getY();z=entity.getZ();heading=entity.getYaw();
            age=entity.age-1;grounded=contact;started=blendStart=now;
            wingAmount=!grounded && entity.deathTime==0?1:0;
        } else if(resumed) {
            // The renderer interpolates only prev/current tick, not the entire time out of view.
            x=entity.prevX;y=entity.prevY;z=entity.prevZ;heading=entity.prevYaw;age=entity.age-1;
            feet.reset();gait.update(0,.05,true);
        }
        if(age!=entity.age) {
            groundCache.clear();
            // The renderer owns a WeakHashMap: a retained probe must not keep its entity key alive.
            var tracked=new java.lang.ref.WeakReference<>(entity);
            var up=modelToWorld.transformDirection(new org.joml.Vector3d(0,1,0)).normalize();
            feet.surfaceProbe(point->{
                var current=tracked.get();if(current==null)return point;
                var from=new net.minecraft.util.math.Vec3d(point.x+up.x*.55,point.y+up.y*.55,point.z+up.z*.55);
                var to=new net.minecraft.util.math.Vec3d(point.x-up.x*.55,point.y-up.y*.55,point.z-up.z*.55);
                var hit=current.getWorld().raycast(new net.minecraft.world.RaycastContext(from,to,
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,current));
                if(hit.getType()!=net.minecraft.util.hit.HitResult.Type.MISS)
                    groundCache.put(Double.doubleToLongBits(point.x)^Long.rotateLeft(Double.doubleToLongBits(point.z),21),hit.getPos().y);
                return hit.getType()==net.minecraft.util.hit.HitResult.Type.MISS?point:new org.joml.Vector3d(hit.getPos().x,hit.getPos().y,hit.getPos().z);
            });
            var translation=new org.joml.Vector3d(entity.getX()-x,entity.getY()-y,entity.getZ()-z);
            var localTranslation=new org.joml.Matrix4d(modelToWorld).invert().transformDirection(new org.joml.Vector3d(translation));
            double distance=contact?Math.hypot(localTranslation.x,localTranslation.z)*dev.flybrain.FlyBrain.config.modelScale*entity.growthScale():translation.length();
            double elapsed=Math.max(1,entity.age-age)/20.0;
            double vertical=(entity.getY()-y)/elapsed;
            if(distance<2 && contact) {
                double heading=Math.toRadians(entity.getYaw()-dev.flybrain.FlyBrain.config.modelYawDegrees);
                double forward=localTranslation.z*dev.flybrain.FlyBrain.config.modelScale*entity.growthScale();
                double scale=dev.flybrain.FlyBrain.config.modelScale*entity.growthScale();
                double turn=Math.toRadians(net.minecraft.util.math.MathHelper.wrapDegrees(entity.getYaw()-this.heading));
                gait.update(Math.copySign(distance,forward)/scale,turn,elapsed,false);
                double acceleration=(forward/scale/elapsed-previousSpeed)/elapsed;
                previousSpeed=forward/scale/elapsed;
                targetLean=(float)Math.clamp(-acceleration*.008,-.065,.065);
                targetBank=(float)Math.clamp(-turn/elapsed*Math.abs(previousSpeed)*.012,-.055,.055);
            }
            else {
                gait.update(0,elapsed,true); // Airborne motion and teleports are not ground strides.
                previousSpeed=0;targetBank=0;
                targetLean=contact?0:(float)Math.clamp(-vertical*.008,-.06,.06);
            }
            x=entity.getX();y=entity.getY();z=entity.getZ();heading=entity.getYaw();age=entity.age;
            if(entity.deathTime>0) {
                select("death",now);
                started=(clockAge-(double)entity.deathTime)/20.0;
            }
            else if(entity.hurtTime>lastHurt) restart("hurt",now);
            else if(entity.handSwinging && !swinging) restart("strike",now);
            else if(grounded && !contact) select(vertical>0 || entity.getVelocity().y>0?"takeoff":"air",now);
            else if(!grounded && contact) select("land",now);
            grounded=contact; lastHurt=entity.hurtTime; swinging=entity.handSwinging;
        }
        if(entity.feeding()!=wasFeeding) {pose.beginTransition();blendStart=now;wasFeeding=entity.feeding();}
        if(Double.isNaN(nextGroom)) nextGroom=now+8+Math.floorMod(entity.getUuid().hashCode(),7);
        if(clip.equals("groom") && (!contact || gait.speed()>.03)) select(contact?"walk":"air",now);
        boolean oneShot=clip.equals("hurt") || clip.equals("land") || clip.equals("takeoff") || clip.equals("strike") || clip.equals("groom");
        if(entity.deathTime==0 && (!oneShot || now-started>=pose.duration(clip))) {
            select(!contact?"air":gait.speed()>.03?(gait.running()?"run":"walk"):"idle",now);
        }
        if(clip.equals("idle") && now>=nextGroom && pose.clips().contains("groom")) {
            select("groom",now);
            nextGroom=now+13+Math.floorMod(entity.getUuid().hashCode()+entity.age,11);
        }
        if(first || resumed)blendStart=now-.12;
        double time=now-started;
        if(clip.equals("walk") || clip.equals("run")) {
            time=gait.phase(partial)*pose.duration(clip);
        }
        feet.update(modelToWorld,gait.phase(partial),now,clip,contact);
        lean+=(targetLean-lean)*(float)-Math.expm1(-dt/.07);
        bank+=(targetBank-bank)*(float)-Math.expm1(-dt/.07);
        double blendDuration=clip.equals("hurt")?.035:clip.equals("strike")?.055:clip.equals("land")?.045:.12;
        float blend=(float)Math.clamp((now-blendStart)/blendDuration,0,1);
        blend=blend*blend*(3-2*blend);
        feet.attitude(entity.deathTime>0?0:lean,entity.deathTime>0?0:bank);
        if(entity.feeding() && entity.isAlive())pose.sampleFeeding(now,blend);else pose.sample(clip,time,blend,feet);
        float target=!contact && entity.deathTime==0?1:0;
        wingAmount+=(target-wingAmount)*(float)-Math.expm1(-dt/(target>0?.045:.07));
        if(wingAmount<.0001) wingAmount=0;
        pose.exposeWings(now,wingAmount);
        CreatureAudio.pose(entity,clip,feet.contactEvents());
        return pose;
    }
    private void select(String next,double now) {
        if(next.equals(clip)) return;
        pose.beginTransition();clip=next;started=blendStart=now;
    }
    private void restart(String next,double now) {
        pose.beginTransition();clip=next;started=blendStart=now;
    }
}
