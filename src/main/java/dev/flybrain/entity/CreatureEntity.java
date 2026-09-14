package dev.flybrain.entity;

import dev.flybrain.FlyBrain;
import dev.flybrain.brain.BrainInput;
import dev.flybrain.brain.BrainOutput;
import dev.flybrain.behavior.PredatorRules;
import dev.flybrain.behavior.PredatorBehavior;
import dev.flybrain.movement.PredatorNavigation;
import dev.flybrain.sensors.SensoryAdapter;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class CreatureEntity extends MobEntity {
    private static final TrackedData<Boolean> PAUSED=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> VISUAL_AGE=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<String> MODE=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> BEHAVIOR=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Boolean> FEEDING=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> GROWTH=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<org.joml.Vector3f> NORMAL=DataTracker.registerData(CreatureEntity.class,TrackedDataHandlerRegistry.VECTOR3F);
    private final PredatorRules.FreshGate freshGate=new PredatorRules.FreshGate();
    public final PredatorNavigation predatorNavigation=new PredatorNavigation(this);
    public final PredatorBehavior predator=new PredatorBehavior(this);
    private long sequence;
    private float blood;
    public BrainInput lastInput;
    public BrainOutput lastOutput=BrainOutput.STOP;
    public double debugDisplacementBps;
    public final dev.flybrain.movement.EscapeImpulse escapeImpulse=new dev.flybrain.movement.EscapeImpulse();
    public double debugJumpImpulse;
    public CreatureEntity(EntityType<? extends CreatureEntity> type, World world) {super(type,world);setPersistent();}
    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH,30)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED,.2).add(EntityAttributes.GENERIC_STEP_HEIGHT,1);
    }
    @Override protected void initGoals() {}
    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);builder.add(PAUSED,true);builder.add(VISUAL_AGE,0);builder.add(MODE,"ground");
        builder.add(BEHAVIOR,"paused");builder.add(FEEDING,false);builder.add(GROWTH,1f);builder.add(NORMAL,new org.joml.Vector3f(0,1,0));
    }
    public boolean animationPaused(){return dataTracker.get(PAUSED);}
    public int visualAge(){return dataTracker.get(VISUAL_AGE);}
    public String visualMode(){return dataTracker.get(MODE);}
    public String behaviorName(){return dataTracker.get(BEHAVIOR);}
    public Vec3d surfaceNormal(){var n=dataTracker.get(NORMAL);return new Vec3d(n.x,n.y,n.z);}
    public boolean feeding(){return dataTracker.get(FEEDING);}
    public float growthScale(){return dataTracker.get(GROWTH);}
    public void setBehavior(String name,boolean feeding){dataTracker.set(BEHAVIOR,name);dataTracker.set(FEEDING,feeding);}
    public void setSurface(String mode,Vec3d normal){dataTracker.set(MODE,mode);dataTracker.set(NORMAL,normal.toVector3f());}
    public void resetGrowth(){blood=0;dataTracker.set(GROWTH,1f);calculateDimensions();}
    public void addBlood(float amount){if(Float.isFinite(amount)&&amount>0){blood=Math.min(24,blood+amount);tryGrowth();}}
    private void tryGrowth(){
        float next=PredatorRules.scale(blood),old=growthScale();if(next<=old)return;
        double ratio=next/old;
        var box=getBoundingBox();double w=box.getLengthX()*ratio/2,h=box.getLengthY()*ratio;
        var grown=new net.minecraft.util.math.Box(getX()-w,getY(),getZ()-w,getX()+w,getY()+h,getZ()+w);
        if(getWorld().isSpaceEmpty(this,grown)){dataTracker.set(GROWTH,next);calculateDimensions();}
    }
    @Override public EntityDimensions getBaseDimensions(EntityPose pose) {
        return super.getBaseDimensions(pose).scaled((FlyBrain.config==null?1:(float)FlyBrain.config.modelScale)*growthScale());
    }
    @Override public void onTrackedDataSet(TrackedData<?> data){super.onTrackedDataSet(data);if(GROWTH.equals(data))calculateDimensions();}
    @Override public void travel(Vec3d input) {
        if(!isAlive()){super.travel(input);return;}
        if(animationPaused()){setVelocity(Vec3d.ZERO);return;}
        if(!predator.routeAllowed(getPos().add(getVelocity())))setVelocity(Vec3d.ZERO);
        move(MovementType.SELF,getVelocity());
        if(horizontalCollision)setVelocity(0,getVelocity().y,0);
        if(verticalCollision)setVelocity(getVelocity().x,0,getVelocity().z);
    }
    @Override public boolean damage(DamageSource source,float amount){
        boolean hit=super.damage(source,amount);
        if(hit&&!getWorld().isClient){predator.hurt();if(source.getAttacker() instanceof LivingEntity living)predator.retaliate(living);}
        return hit;
    }
    @Override public void tick() {
        if(!getWorld().isClient && isAlive()) {
            var config=FlyBrain.config;var brains=FlyBrain.brains;
            if(brains!=null && age%Math.max(1,20/config.sensoryHz)==0){lastInput=SensoryAdapter.sample(this,++sequence,config.sensoryRange);brains.submit(getUuid(),lastInput);}
            var result=brains==null?null:brains.result(getUuid());
            boolean wasPaused=animationPaused();boolean running=freshGate.update(result==null?-1:result.output().sequence());
            dataTracker.set(PAUSED,!running);lastOutput=running?result.output():BrainOutput.STOP;
            if(!running){setVelocity(Vec3d.ZERO);predator.pause();}
            else {
                if(wasPaused)predatorNavigation.clear();
                dataTracker.set(VISUAL_AGE,visualAge()+1);predator.tick();
                predatorNavigation.tick(Math.max(0,Math.min(1,(lastOutput.forward()+1)/2)));
                if(visualAge()%20==0)tryGrowth();
            }
        }
        double beforeX=getX(),beforeZ=getZ();super.tick();
        debugDisplacementBps=Math.hypot(getX()-beforeX,getZ()-beforeZ)*20;
        if(!getWorld().isClient && FlyBrain.config.logging && age%20==0)FlyBrain.record(this);
    }
    @Override public void writeCustomDataToNbt(NbtCompound nbt){super.writeCustomDataToNbt(nbt);nbt.putFloat("FlyBrainBlood",blood);nbt.putFloat("FlyBrainGrowth",growthScale());}
    @Override public void readCustomDataFromNbt(NbtCompound nbt){super.readCustomDataFromNbt(nbt);blood=Math.max(0,Math.min(24,nbt.getFloat("FlyBrainBlood")));dataTracker.set(GROWTH,Math.max(1,Math.min(PredatorRules.scale(blood),nbt.getFloat("FlyBrainGrowth"))));calculateDimensions();}
    @Override public void remove(RemovalReason reason){if(!getWorld().isClient&&FlyBrain.brains!=null)FlyBrain.brains.remove(getUuid());super.remove(reason);}
}


