package dev.flybrain.render;

import dev.flybrain.FlyBrain;
import dev.flybrain.entity.CreatureEntity;
import dev.flybrain.audio.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.*;
import net.minecraft.sound.SoundCategory;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Audio follows tracked creatures even outside the camera frustum. */
public final class CreatureAudio {
    private static final WeakHashMap<CreatureEntity,State> STATES=new WeakHashMap<>();
    private static Object world;
    private static final class State {
        final SoundCueClock cues=new SoundCueClock();
        String clip="idle";int contacts,renderAge=-100;Buzz buzz;
        net.minecraft.util.math.Vec3d position;
        final dev.flybrain.model.GaitClock gait=new dev.flybrain.model.GaitClock();
        boolean airborne;
    }
    private CreatureAudio(){}
    static void pose(CreatureEntity entity,String clip,int contacts){var s=STATES.computeIfAbsent(entity,k->new State());s.clip=clip;s.contacts=contacts;s.renderAge=entity.age;}
    public static void initialize(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(world!=client.world){for(var s:STATES.values())if(s.buzz!=null)s.buzz.finish();STATES.clear();world=client.world;}
        if(client.world==null)return;
        for(var entity:client.world.getEntities())if(entity instanceof CreatureEntity fly){
            var state=STATES.computeIfAbsent(fly,k->new State());
            boolean air=fly.isAlive()&&fly.visualMode().equals("air");
            double distance=state.position==null?0:state.position.distanceTo(fly.getPos());state.position=fly.getPos();
            if(fly.animationPaused() && fly.isAlive()) {
                if(state.buzz!=null){state.buzz.finish();state.buzz=null;}
                continue; // Freeze physical contact history: resuming midair is not another takeoff.
            }
            double before=state.gait.phase(1);
            state.gait.update(distance/(FlyBrain.config.modelScale*fly.growthScale()),.05,air||fly.animationPaused()||distance>2);
            int strikes=(int)Math.max(0,Math.floor(state.gait.phase(1)*2)-Math.floor(before*2));
            // ponytail: off-camera foley follows the same distance gait; visible contact events remain exact.
            if(fly.age-state.renderAge>1){
                state.contacts+=strikes;
                state.clip=air?(state.airborne?"air":"takeoff"):state.airborne?"land":distance>.001?(state.gait.running()?"run":"walk"):"idle";
            }
            if(air && (state.buzz==null || state.buzz.isDone())){state.buzz=new Buzz(fly);client.getSoundManager().play(state.buzz);}
            if(!air && state.buzz!=null){state.buzz.finish();state.buzz=null;}
            state.airborne=air;
            String clip=!fly.isAlive()?"death":fly.hurtTime>0?"hurt":fly.feeding()?"feeding":
                    (fly.behaviorName().equals("orient")||fly.behaviorName().equals("reaction test"))?"scare":state.clip;
            if((fly.visualMode().equals("wall")||fly.visualMode().equals("ceiling"))&&(clip.equals("walk")||clip.equals("run")))clip="crawl";
            for(String cue:state.cues.update(clip,state.contacts,fly.animationPaused()&&fly.isAlive(),fly.visualAge()+fly.deathTime)) {
                double gain=cue.equals("scare")?FlyBrain.config.scareVolume:cue.equals("idle")?FlyBrain.config.ambienceVolume:FlyBrain.config.movementVolume;
                client.getSoundManager().play(new EntityTrackingSoundInstance(CreatureSounds.get(cue),SoundCategory.HOSTILE,(float)gain,
                        1-(fly.growthScale()-1)*.9f,fly,fly.getUuid().getLeastSignificantBits()+fly.visualAge()) {
                    @Override public void tick(){super.tick();if(fly.isAlive()&&fly.animationPaused())setDone();}
                });
            }
        }
    });}
    private static final class Buzz extends MovingSoundInstance {
        private final WeakReference<CreatureEntity> entity;
        Buzz(CreatureEntity fly){super(CreatureSounds.get("air"),SoundCategory.HOSTILE,net.minecraft.util.math.random.Random.create());
            entity=new WeakReference<>(fly);repeat=true;repeatDelay=0;tick();}
        void finish(){setDone();}
        @Override public void tick(){
            var fly=entity.get();
            if(fly==null||fly.isRemoved()||!fly.isAlive()||fly.animationPaused()||!fly.visualMode().equals("air")){setDone();return;}
            x=fly.getX();y=fly.getBodyY(.5);z=fly.getZ();
            volume=(float)(FlyBrain.config.movementVolume*.55);pitch=1-(fly.growthScale()-1)*.9f;
        }
    }
}
