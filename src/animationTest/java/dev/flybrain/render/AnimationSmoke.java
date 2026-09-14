package dev.flybrain.render;

import dev.flybrain.FlyBrain;
import dev.flybrain.entity.CreatureEntity;
import dev.flybrain.model.GaitClock;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Client-only integration fixture, excluded from release. No connection to the neural process.
 * Prescribed trajectories exercise the real Minecraft renderer, not gameplay behavior. */
public final class AnimationSmoke implements ClientModInitializer {
    private CreatureEntity fly;
    private Vec3d origin;
    private int ticks,waiting;
    private long previousFrame;
    private float[] previousPose;
    private Field animations,clip;
    private final StringBuilder csv=new StringBuilder("age,partial,clip,frame_ms,body_step,wing_step,wing_activity,x,y,z\n");
    private final Set<String> seen=new TreeSet<>();
    private int frames;
    private int captures;
    private long lastCapture;
    private String label="Loading";
    private double pathX,pathZ,facing=180;
    private final StringBuilder checks=new StringBuilder();

    @Override public void onInitializeClient() {
        try {
            animations=CreatureRenderer.class.getDeclaredField("animations");animations.setAccessible(true);
            clip=CreatureAnimation.class.getDeclaredField("clip");clip.setAccessible(true);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context,counter)->{
            var client=MinecraftClient.getInstance();if(fly==null)return;
            context.drawTextWithShadow(client.textRenderer,"RENDER REGRESSION | "+label+" | prescribed motion, not AI",10,10,0xffffff);
            long now=System.nanoTime();
            if(now-lastCapture>=100_000_000L){lastCapture=now;
                net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory,
                        String.format(Locale.ROOT,"predator-%05d.png",captures++),client.getFramebuffer(),message->{});
            }
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context->{
            if(fly==null)return;
            var client=MinecraftClient.getInstance();
            try {
                var renderer=client.getEntityRenderDispatcher().getRenderer(fly);
                var animation=(CreatureAnimation)((Map<?,?>)animations.get(renderer)).get(fly);
                if(animation==null)return; // The real dispatcher has not rendered the entity yet.
                String state=(String)clip.get(animation);seen.add(state);label=fly.feeding()?"feeding":state;
                long now=System.nanoTime();double frameMs=previousFrame==0?0:(now-previousFrame)/1e6;previousFrame=now;
                double body=0,wing=0;
                var pose=animation.pose;
                for(int i=0;i<pose.positions.length;i++) {
                    if(!Float.isFinite(pose.positions[i]))throw new IllegalStateException("Nonfinite live pose");
                    if(previousPose!=null) {
                        double d=Math.abs(pose.positions[i]-previousPose[i]);
                        if(pose.wingVertices[i/3])wing=Math.max(wing,d);else body=Math.max(body,d);
                    }
                }
                previousPose=pose.positions.clone();frames++;
                csv.append(String.format(Locale.ROOT,"%d,%.6f,%s,%.4f,%.6f,%.6f,%.6f,%.5f,%.5f,%.5f%n",
                        fly.age,context.tickCounter().getTickDelta(false),state,frameMs,body,wing,animation.wingAmount(),fly.getX(),fly.getY(),fly.getZ()));
            } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
        });
    }

    private void tick(MinecraftClient client) {
        if(client.world==null || client.player==null)return;
        if(fly==null) {
            if(++waiting<100)return; // Let spawn chunks finish building before measuring.
            var direction=Vec3d.fromPolar(0,client.player.getYaw());
            origin=client.player.getPos().add(direction.multiply(6));
            fly=new CreatureEntity(FlyBrain.CREATURE,client.world);
            visual(fly,0);
            fly.setId(-700001);fly.setUuid(new UUID(0,0));fly.setNoGravity(true);fly.noClip=true;
            fly.refreshPositionAndAngles(origin.x,origin.y,origin.z,180,0);fly.setOnGround(true);
            client.world.addEntity(fly);
            edgeChecks(client);
        }
        double t=ticks++/20.0,speed=0,height=0;boolean ground=true;
        visual(fly,Math.min(ticks,1000));
        if(t>=16 && t<23)speed=.5;
        else if(t>=23 && t<27) {speed=2.2;facing+=Math.toDegrees(1.1)/20;}
        else if(t>=27 && t<31)facing+=90/20.0;
        else if(t>=31 && t<35)speed=-.5;
        else if(t>38 && t<42) {height=1.3*Math.sin(Math.PI*(t-38)/4);ground=false;}
        else if(t>=43 && t<47)speed=2;
        pathX-=Math.sin(Math.toRadians(facing))*speed/20;pathZ+=Math.cos(Math.toRadians(facing))*speed/20;
        var next=origin.add(pathX,height,pathZ);float yaw=(float)facing;
        // Minecraft velocity is blocks/tick, not blocks/second.
        fly.setVelocity(next.subtract(new Vec3d(fly.prevX,fly.prevY,fly.prevZ)));
        fly.setPosition(next);fly.setYaw(yaw);fly.setBodyYaw(yaw);fly.setHeadYaw(yaw);fly.setOnGround(ground);
        var view=next.subtract(client.player.getPos());
        client.player.setYaw((float)Math.toDegrees(Math.atan2(-view.x,view.z)));
        if(ticks==881)fly.hurtTime=10;
        if(ticks==921)fly.swingHand(Hand.MAIN_HAND);
        feeding(fly,t>=47&&t<49);
        if(t>=50)fly.deathTime=(int)((t-50)*20)+1;
        if(t>=51) {
            try {
                Path out=Path.of("..");Files.writeString(out.resolve("live-frames.csv"),csv);
                Files.writeString(out.resolve("live-result.txt"),"MINECRAFT_RENDER_COMPLETED frames="+frames+" clips="+seen+"\n");
                Files.writeString(out.resolve("live-edge-checks.txt"),checks);
                if(!seen.containsAll(Set.of("idle","walk","run","groom","takeoff","air","land","strike","hurt","death")))
                    throw new IllegalStateException("Missing rendered clips: "+seen);
            } catch(java.io.IOException e) {throw new IllegalStateException(e);}
            client.scheduleStop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void feeding(CreatureEntity entity,boolean active) {
        try {
            var field=CreatureEntity.class.getDeclaredField("FEEDING");field.setAccessible(true);
            entity.getDataTracker().set((net.minecraft.entity.data.TrackedData<Boolean>)field.get(null),active);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    @SuppressWarnings("unchecked")
    private static void visual(CreatureEntity entity,int age) {
        try {
            var paused=CreatureEntity.class.getDeclaredField("PAUSED");paused.setAccessible(true);
            var clock=CreatureEntity.class.getDeclaredField("VISUAL_AGE");clock.setAccessible(true);
            entity.getDataTracker().set((net.minecraft.entity.data.TrackedData<Boolean>)paused.get(null),false);
            entity.getDataTracker().set((net.minecraft.entity.data.TrackedData<Integer>)clock.get(null),age);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    private void edgeChecks(MinecraftClient client) {
        try {
            var entity=new CreatureEntity(FlyBrain.CREATURE,client.world);
            entity.refreshPositionAndAngles(origin.x,origin.y+.04,origin.z,0,0);
            entity.setOnGround(true);entity.age=200;visual(entity,200);
            var animation=new CreatureAnimation(FlyBrainClient.model);
            var world=new org.joml.Matrix4d().translation(entity.getX(),entity.getY(),entity.getZ());
            animation.update(entity,.5f,world);
            var cache=CreatureAnimation.class.getDeclaredField("groundCache");cache.setAccessible(true);
            var heights=(Map<?,?>)cache.get(animation);
            if(heights.isEmpty())throw new IllegalStateException("First frame skipped ground probes");
            for(var value:heights.values())if(Math.abs(((Number)value).doubleValue()-origin.y)>.001)
                throw new IllegalStateException("Terrain probe missed actual flat-world ground: "+value);
            checks.append("FIRST_FRAME_TERRAIN_PASS actual Minecraft raycasts=").append(heights.size()).append('\n');
            entity.age+=20;visual(entity,220);entity.setPosition(origin.add(1,.04,0));
            entity.prevX=entity.getX()-.025;entity.prevY=entity.getY();entity.prevZ=entity.getZ();
            animation.update(entity,.25f,new org.joml.Matrix4d().translation(entity.getX(),entity.getY(),entity.getZ()));
            var clock=CreatureAnimation.class.getDeclaredField("gait");clock.setAccessible(true);
            var gait=(GaitClock)clock.get(animation);
            double delta=gait.phase(1)-gait.phase(0);
            if(Math.abs(delta)>.1)throw new IllegalStateException("Offscreen travel compressed into next tick: "+delta);
            checks.append("REENTRY_PHASE_PASS latestTickCycles=").append(delta).append('\n');
            entity.deathTime=19;
            var dying=new CreatureAnimation(FlyBrainClient.model);dying.update(entity,0,world);
            if(!clip.get(dying).equals("death"))throw new IllegalStateException("First-visible death not selected");
            var terminal=new dev.flybrain.model.GlbPose(FlyBrainClient.model);terminal.sample("death",100,1);
            double error=0;for(int i=0;i<terminal.positions.length;i++)error=Math.max(error,Math.abs(terminal.positions[i]-dying.pose.positions[i]));
            if(error>.00002)throw new IllegalStateException("Late death restarted collapse: "+error);
            checks.append("FIRST_VISIBLE_DEATH_PASS maxError=").append(error).append('\n');
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
    }
}

