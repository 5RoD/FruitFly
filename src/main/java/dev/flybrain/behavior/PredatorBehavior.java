package dev.flybrain.behavior;

import dev.flybrain.entity.CreatureEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Game logic owns intention; fresh measured neural output modulates execution. */
public final class PredatorBehavior {
    private final CreatureEntity mob;
    public final PredatorRules.Drives drives=new PredatorRules.Drives();
    private final PredatorRules.Feint feint=new PredatorRules.Feint();
    private Vec3d feintStart,feintGoal;
    private final Map<UUID,PredatorRules.Memory<BlockPos>> memory=new LinkedHashMap<>();
    private LivingEntity target;
    private net.minecraft.server.network.ServerPlayerEntity sceneActor;
    private Vec3d lastSeen,sceneOrigin,retreatGoal,stalkGoal;
    private int seenAt,endsAt,retreatUntil,feedTick=-1,orientUntil,pursuitFailed,watchedTicks,strikeUntil;
    private boolean scene,retaliation,approaching;
    private int reactionUntil,reactionReady;
    private double reactionDistance;
    private boolean reactionWatched;
    private final SceneBreach breach;
    public PredatorBehavior(CreatureEntity mob){this.mob=mob;breach=new SceneBreach(mob);}
    public boolean sceneActive(){return scene;}
    public boolean routeAllowed(Vec3d point){return !scene||point.squaredDistanceTo(sceneOrigin)<=(approaching?4096:1024);}
    public void hurt(){drives.hurt();interrupt();}
    public void startScene(Vec3d origin,LivingEntity initial){stop();breach.reset();scene=true;sceneActor=initial instanceof net.minecraft.server.network.ServerPlayerEntity p?p:null;sceneOrigin=origin;approaching=mob.squaredDistanceTo(origin)>900;endsAt=mob.visualAge()+1200;orientUntil=mob.visualAge()+40+mob.getRandom().nextInt(41);target=initial!=null&&observed(initial)&&initial.squaredDistanceTo(origin)<=256?initial:null;}
    public void stop(){feint.interrupt();reactionUntil=0;sceneActor=null;approaching=false;scene=false;retaliation=false;retreatUntil=0;watchedTicks=0;pursuitFailed=0;target=null;feedTick=-1;strikeUntil=0;lastSeen=null;stalkGoal=null;retreatGoal=null;mob.predatorNavigation.clear();breach.cancel();mob.setBehavior("idle",false);}
    public void interrupt(){feint.interrupt();feedTick=-1;strikeUntil=0;retreatGoal=null;breach.cancel();retreatUntil=mob.visualAge()+120+mob.getRandom().nextInt(81);}
    public void retaliate(LivingEntity attacker){if(attacker!=mob&&!(attacker instanceof CreatureEntity)){target=attacker;retaliation=true;endsAt=mob.visualAge()+400;}}
    public void pause(){feint.interrupt();feedTick=-1;strikeUntil=0;breach.cancel();mob.setBehavior("paused",false);}
    private boolean valid(LivingEntity e){return e!=null&&e.isAlive()&&!(e instanceof CreatureEntity)&&!(e instanceof PlayerEntity p&&p.isSpectator());}
    private boolean observed(LivingEntity e){Vec3d delta=e.getEyePos().subtract(mob.getEyePos());return delta.lengthSquared()<=256&&mob.canSee(e)&&(delta.lengthSquared()<6.25||mob.getRotationVec(1).dotProduct(delta.normalize())>.15);}
    private boolean watched(PlayerEntity p){Vec3d delta=mob.getEyePos().subtract(p.getEyePos());return delta.lengthSquared()<256&&p.canSee(mob)&&p.getRotationVec(1).dotProduct(delta.normalize())>.75;}
    public void tick(){
        int now=mob.visualAge();
        boolean sensed=valid(target)&&observed(target);
        drives.advance(sensed,sensed&&target instanceof PlayerEntity p&&watched(p),mob.predatorNavigation.failed(),mob.lastOutput.ascend());
        if(scene && (now>=endsAt || !approaching&&mob.squaredDistanceTo(sceneOrigin)>1024 || lastSeen!=null&&lastSeen.squaredDistanceTo(sceneOrigin)>1024)){stop();retreatUntil=now+160;}
        if(!scene && target!=null&&now>=endsAt){target=null;retaliation=false;lastSeen=null;retreatUntil=now+300+mob.getRandom().nextInt(401);}
        if(!valid(target)){feint.interrupt();reactionUntil=0;retaliation=false;target=null;feedTick=-1;strikeUntil=0;lastSeen=null;stalkGoal=null;retreatGoal=null;}
        if(scene&&approaching){
            if(mob.squaredDistanceTo(sceneOrigin)>900){mob.setBehavior("scene approach",false);mob.predatorNavigation.toward(sceneOrigin);return;}
            approaching=false;mob.predatorNavigation.clear();orientUntil=now+40+mob.getRandom().nextInt(41);
        }
        if(scene && now<orientUntil){Vec3d look=sceneOrigin.subtract(mob.getPos());float desired=(float)Math.toDegrees(Math.atan2(-look.x,look.z));float delta=net.minecraft.util.math.MathHelper.wrapDegrees(desired-mob.getYaw());mob.setYaw(mob.getYaw()+Math.max(-5,Math.min(5,delta)));mob.setHeadYaw(mob.getYaw());mob.setBodyYaw(mob.getYaw());mob.setBehavior("orient",false);return;}
        if(now<retreatUntil){
            mob.setBehavior("retreat",false);
            if(lastSeen!=null){if(retreatGoal==null||mob.predatorNavigation.failed()&&now%40==0)retreatGoal=mob.predatorNavigation.coverAway(lastSeen,retreatGoal);mob.predatorNavigation.toward(retreatGoal);}
            return;
        }
        if(target==null&&now%20==0){
            if(scene)target=mob.getWorld().getEntitiesByClass(LivingEntity.class,new net.minecraft.util.math.Box(sceneOrigin,sceneOrigin).expand(16),e->valid(e)&&e.squaredDistanceTo(sceneOrigin)<=256&&observed(e))
                    .stream().min(Comparator.comparingDouble(mob::squaredDistanceTo)).orElse(null);
            else target=mob.getWorld().getEntitiesByClass(PlayerEntity.class,mob.getBoundingBox().expand(16),e->valid(e)&&observed(e)).stream().min(Comparator.comparingDouble(mob::squaredDistanceTo)).orElse(null);
            if(target!=null&&!observed(target))target=null;
            if(target!=null&&!scene)endsAt=now+300+mob.getRandom().nextInt(1+(int)(400*drives.curiosity()));
        }
        if(target==null){mob.setBehavior(scene?"scene search":"idle",false);if(scene)mob.predatorNavigation.toward(sceneOrigin);return;}
        boolean visible=observed(target);
        if(visible){
            lastSeen=target.getPos();seenAt=now;
            if(target instanceof PlayerEntity){
                memory.values().removeIf(m->m.size(now)==0);
                // ponytail: remember at most 32 nearby players; oldest map entry is dispensable curiosity evidence.
                if(!memory.containsKey(target.getUuid())&&memory.size()>=32)memory.remove(memory.keySet().iterator().next());
                if(now%40==0)memory.computeIfAbsent(target.getUuid(),id->new PredatorRules.Memory<>()).observe(target.getBlockPos().toImmutable(),now);
            }
        }
        if(lastSeen==null || now-seenAt>200){var remembered=memory.get(target.getUuid());var places=remembered==null?java.util.List.<BlockPos>of():remembered.places(now);feint.interrupt();reactionUntil=0;retaliation=false;target=null;feedTick=-1;if(!places.isEmpty()&&!scene)mob.predatorNavigation.toward(places.get(places.size()-1).toCenterPos());else mob.predatorNavigation.clear();mob.setBehavior("remembered location search",false);return;}
        boolean aggressive=scene||retaliation;
        if(feedTick>=0){
            if(!visible || mob.squaredDistanceTo(target)>9){interrupt();return;}
            damagePulse(PredatorRules.feedingDamage(feedTick));mob.setBehavior("feeding",true);
            if(feedTick++>=40){interrupt();}return;
        }
        if(aggressive && visible && mob.squaredDistanceTo(target)<=6.25 && !mob.isOnGround()){
            if(strikeUntil==0){strikeUntil=now+10;mob.swingHand(net.minecraft.util.Hand.MAIN_HAND);mob.setBehavior("strike anticipation",false);mob.predatorNavigation.clear();return;}if(now<strikeUntil)return;strikeUntil=0;feedTick=0;mob.predatorNavigation.clear();damagePulse(PredatorRules.feedingDamage(feedTick++));mob.setBehavior("feeding",true);return;
        }
        if(!aggressive){
            // The sustained watched response takes priority over both optional reaction tests and feints.
            if(drives.startled()){feint.interrupt();reactionUntil=0;mob.setBehavior("conceal",false);retreatGoal=null;retreatUntil=now+300+mob.getRandom().nextInt(401);return;}
            if(reactionUntil>0){
                mob.predatorNavigation.clear();mob.setBehavior("reaction test",false);
                if(now<reactionUntil)return;
                reactionUntil=0;
                if(visible){
                    double distance=mob.getPos().distanceTo(lastSeen);
                    boolean approached=distance<reactionDistance-1,looked=target instanceof PlayerEntity p&&watched(p)&&!reactionWatched,withdrew=distance>reactionDistance+1;
                    drives.reacted(approached,looked,withdrew);
                    if(approached||looked){retreatGoal=null;retreatUntil=now+300+mob.getRandom().nextInt(401);return;}
                }
            }
            if(feint.phase()==0&&visible&&now>=reactionReady&&mob.squaredDistanceTo(target)>16&&mob.getRandom().nextInt(200)==0){
                reactionReady=now+600;reactionUntil=now+40;reactionDistance=mob.getPos().distanceTo(lastSeen);reactionWatched=target instanceof PlayerEntity p&&watched(p);
                mob.predatorNavigation.clear();mob.setBehavior("reaction test",false);return;
            }
            if(feint.phase()==0&&visible&&reactionUntil==0&&drives.confidence()>.4&&mob.squaredDistanceTo(target)>16&&mob.getRandom().nextInt(400)==0&&feint.begin(now)){
                feintStart=mob.getPos();feintGoal=mob.predatorNavigation.coverAway(lastSeen,null);
            }
            if(feint.phase()!=0){
                int previous=feint.phase();feint.advance(now,now-seenAt<=200,feintStart.squaredDistanceTo(mob.getPos())>1);
                if(feint.phase()==2&&previous==1)feintGoal=mob.predatorNavigation.alternateApproach(lastSeen,feintStart);
                if(feint.phase()!=0){mob.setBehavior(feint.phase()==1?"false retreat":"alternate approach",false);mob.predatorNavigation.toward(feintGoal);return;}
            }
            watchedTicks=target instanceof PlayerEntity player&&watched(player)?watchedTicks+1:0;

            mob.setBehavior("curious stalk",false);
            Vec3d away=mob.getPos().subtract(lastSeen);double distance=away.length();
            double standoff=7+2*(1-drives.confidence())+3*drives.fear()-2*drives.hunger();
            if(stalkGoal==null||now%40==0)stalkGoal=mob.predatorNavigation.ceilingNear(lastSeen.add(away.normalize().multiply(standoff)));
            if(distance>7||stalkGoal.y>mob.getY()+.5)mob.predatorNavigation.toward(stalkGoal);
            else mob.predatorNavigation.clear();
        }else{
            mob.setBehavior(visible?"pursue":"last seen search",false);
            mob.predatorNavigation.toward(lastSeen.add(0,visible?Math.max(.5,target.getHeight()*.55):0,0));
            if(mob.predatorNavigation.failed() || mob.horizontalCollision)pursuitFailed++;else pursuitFailed=0;
            if(scene&&pursuitFailed>=100&&now-seenAt<=200)breach.tick(lastSeen,sceneActor);else breach.cancel();
        }
    }
    private void damagePulse(int amount){
        if(amount<=0 || !valid(target) || !mob.canSee(target)||mob.squaredDistanceTo(target)>9)return;
        if(target instanceof PlayerEntity player && player.isCreative())return;
        float before=target.getHealth();target.damage(mob.getDamageSources().mobAttack(mob),amount);
        float lost=Math.max(0,before-target.getHealth());
        boolean bloodTarget=target instanceof PlayerEntity||target instanceof AnimalEntity||target instanceof MerchantEntity;
        if(bloodTarget)drives.fed(lost);
        if(bloodTarget&&lost>0&&mob.getWorld() instanceof net.minecraft.server.world.ServerWorld world){Vec3d blood=target.getEyePos(),towardMouth=mob.getEyePos().subtract(blood).normalize().multiply(.22);for(int i=0;i<3;i++)world.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(.55f,.015f,.025f),.7f),blood.x,blood.y+i*.035,blood.z,0,towardMouth.x,towardMouth.y,towardMouth.z,1);}
        if(bloodTarget)mob.addBlood(lost);
    }
}












