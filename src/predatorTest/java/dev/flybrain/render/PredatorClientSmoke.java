package dev.flybrain.render;

import dev.flybrain.entity.CreatureEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import java.nio.file.*;
import java.util.*;

/** Isolated live-client observation; no fabricated movement or neural evidence. */
public final class PredatorClientSmoke implements ClientModInitializer {
    private int ticks;
    private long previous,lastCapture;
    private int captureCount;
    private String label="Waiting for server creature";
    private CreatureEntity observed;
    private final StringBuilder log=new StringBuilder("frame_ms,world_tick,entity,mode,behavior,growth,paused,feeding,x,y,z,player_x,player_y,player_z,health\n");
    @Override public void onInitializeClient(){
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(client.world==null||client.player==null)return;ticks++;
            CreatureEntity closest=null;
            for(var e:client.world.getEntities())if(e instanceof CreatureEntity fly && fly.getCustomName()!=null && fly.getCustomName().getString().equals("PREDATOR_QA_ACTIVE"))closest=fly;
            observed=closest;
            if(closest!=null){
                var direction=closest.getPos().add(0,closest.getHeight()*.5,0).subtract(client.player.getEyePos());
                client.player.setYaw((float)Math.toDegrees(Math.atan2(-direction.x,direction.z)));
                client.player.setPitch((float)-Math.toDegrees(Math.atan2(direction.y,direction.horizontalLength())));
                label=closest.visualMode()+" | "+closest.behaviorName()+" | growth "+closest.growthScale()+" | paused "+closest.animationPaused();
            }
            // Two real clients send ordinary movement input; camera tracking is QA-only.
            int step=ticks%160;
            client.options.forwardKey.setPressed(step<12);
            client.options.backKey.setPressed(step>=80&&step<92);
            if(ticks>=1800){
                client.options.forwardKey.setPressed(false);client.options.backKey.setPressed(false);
                try{Files.writeString(Path.of("client-observation.csv"),log);}catch(java.io.IOException e){throw new IllegalStateException(e);}
                client.scheduleStop();
            }
        });
        HudRenderCallback.EVENT.register((context,counter)->{
            var client=MinecraftClient.getInstance();if(client.world==null||client.player==null)return;
            context.drawTextWithShadow(client.textRenderer,"PREDATOR LIVE CLIENT: "+label,12,12,0xffffff);
            long now=System.nanoTime();
            var fly=observed;
            if(previous!=0 && fly!=null)log.append(String.format(Locale.ROOT,"%.4f,%d,%s,%s,%s,%.4f,%s,%s,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.2f%n",
                    (now-previous)/1e6,client.world.getTime(),fly.getUuid(),fly.visualMode(),fly.behaviorName(),fly.growthScale(),fly.animationPaused(),fly.feeding(),
                    fly.getX(),fly.getY(),fly.getZ(),client.player.getX(),client.player.getY(),client.player.getZ(),client.player.getHealth()));
            previous=now;
            if(ticks>40 && now-lastCapture>=100_000_000L && client.getSession().getUsername().equals("PredatorQA")){
                lastCapture=now;
                String name="tour-"+String.format(Locale.ROOT,"%05d",captureCount++)+".png";
                ScreenshotRecorder.saveScreenshot(client.runDirectory,name,client.getFramebuffer(),message->{});
            }
        });
    }
}
