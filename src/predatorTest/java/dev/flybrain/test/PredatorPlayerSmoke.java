package dev.flybrain.test;

import dev.flybrain.entity.CreatureEntity;
import dev.flybrain.scene.SceneMarkers;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.Consumer;

/** Controlled two-player world checks, then an actual navigation ceiling/pause tour. */
final class PredatorPlayerSmoke {
    private final CreatureEntity fly;
    private final ServerWorld world;
    private final Consumer<Boolean> online;
    private final Consumer<String> result;
    private int stage,ticks;
    private ServerPlayerEntity a,b;
    private final BlockPos marker=new BlockPos(29,101,6),other=new BlockPos(25,101,6);
    private String id;
    private float health;
    private boolean fed;
    private Vec3d ceiling,frozen;
    private int frozenAge;
    PredatorPlayerSmoke(CreatureEntity fly,ServerWorld world,Consumer<Boolean> online,Consumer<String> result){this.fly=fly;this.world=world;this.online=online;this.result=result;}
    void tick()throws Exception{
        if(stage==0){
            a=world.getPlayers().stream().filter(p->p.getGameProfile().getName().equals("PredatorQA")).findFirst().orElse(null);
            b=world.getPlayers().stream().filter(p->p.getGameProfile().getName().equals("GuestQA")).findFirst().orElse(null);
            if(a==null||b==null)return;
            fly.predator.stop();place(marker,a);id=selected(a);
            var shown=SceneMarkers.class.getDeclaredField("shown");shown.setAccessible(true);require(!((Set<?>)shown.get(null)).contains(a.getUuid()),"placement did not auto-show editor");
            step(1,"two named clients joined; marker placed without auto-show");
        }
        ticks++;
        if(stage==1){
            teleport(a,marker.toCenterPos().add(12,1,0));teleport(b,marker.toCenterPos().add(-12,1,0));
            if(ticks>=50){teleport(a,marker.toCenterPos().add(0,1,2));step(2,"40-tick outside dwell completed");}
        }else if(stage==2){
            teleport(a,marker.toCenterPos().add(0,1,2));
            if(ticks>=5){
                require(fly.predator.sceneActive(),"reentry starts scene");
                var stateMethod=SceneMarkers.class.getDeclaredMethod("state",ServerWorld.class);stateMethod.setAccessible(true);
                var saved=(net.minecraft.world.PersistentState)stateMethod.invoke(null,world);NbtCompound nbt=saved.writeNbt(new NbtCompound(),world.getRegistryManager());
                var entries=nbt.getList("markers",10);boolean spent=false;for(int i=0;i<entries.size();i++)if(entries.getCompound(i).getString("id").equals(id))spent=entries.getCompound(i).getBoolean("spent");
                require(spent,"spent persisted in world NBT");place(other,a);command(a,"flybrain scene stop");require(fly.predator.sceneActive(),"selected unrelated stop preserves active scene");
                command(a,"flybrain scene select "+id);command(a,"flybrain scene stop");require(!fly.predator.sceneActive(),"selected stop ends scene");command(a,"flybrain scene trigger");require(!fly.predator.sceneActive(),"spent trigger rejected");
                command(a,"flybrain scene reset");command(a,"flybrain scene trigger");require(fly.predator.sceneActive(),"explicit reset permits trigger");world.removeBlock(marker,false);require(!fly.predator.sceneActive(),"removing marker cancels encounter");
                step(3,"reentry, persisted one-shot, unrelated stop, explicit reset and block-removal cancellation");
                fly.refreshPositionAndAngles(30.5,103,2.5,180,0);fly.setVelocity(Vec3d.ZERO);teleport(a,new Vec3d(30.5,103,.5));teleport(b,new Vec3d(15.5,105,2.5));health=a.getHealth();fly.predator.startScene(fly.getPos(),a);
            }
        }else if(stage==3){
            teleport(a,new Vec3d(30.5,103,.5));fed|=fly.feeding();
            if(fed&&!fly.feeding()){
                require(a.getHealth()==health,"creative encounter preserves actual health");fly.predator.stop();
                for(int x=26;x<=39;x++)for(int z=-2;z<=12;z++)world.setBlockState(new BlockPos(x,109,z),Blocks.STONE.getDefaultState());
                ceiling=new Vec3d(34.5,109-fly.getHeight()-.02,5.5);step(4,"creative feeding presentation changes no health; ceiling tour begins");
            }
            require(ticks<240,"creative feeding presentation timeout");
        }else if(stage==4){
            // Observers stay beyond perception range; gameplay navigator, collision and tracked modes remain real.
            teleport(a,new Vec3d(15.5,108,5.5));teleport(b,new Vec3d(15.5,108,8.5));fly.predatorNavigation.toward(ceiling);
            if(fly.getPos().squaredDistanceTo(ceiling)<.02&&fly.visualMode().equals("ceiling")){online.accept(false);step(5,"actual ceiling attachment reached; transport outage begins");}
            require(ticks<400,"ceiling navigation timeout at "+fly.getPos()+" mode="+fly.visualMode());
        }else if(stage==5){
            if(fly.animationPaused()){frozen=fly.getPos();frozenAge=fly.visualAge();step(6,"ceiling paused");}
        }else if(stage==6){
            require(fly.getPos().squaredDistanceTo(frozen)<1e-12&&fly.visualAge()==frozenAge,"ceiling physics and visual clock frozen");
            if(ticks>=30){online.accept(true);step(7,"ceiling frozen 30 ticks; recovery begins");}
        }else if(stage==7&&!fly.animationPaused()){
            result.accept("TWO_PLAYER_SMOKE_PASS marker lifecycle, creative health, ceiling attachment and paused/resumed clock");step(8,"client camera tour: descend under actual navigation");
        }else if(stage==8){
            fly.predatorNavigation.toward(new Vec3d(35.5,101,9.5));
            if(ticks>180)step(9,"client tour complete");
        }
    }
    private void step(int next,String message){stage=next;ticks=0;result.accept("worldTick="+world.getTime()+" playerStage="+stage+" fly="+fly.getUuid()+" "+message);}
    private void teleport(ServerPlayerEntity p,Vec3d at){p.teleport(world,at.x,at.y,at.z,135,10);}
    private void place(BlockPos pos,ServerPlayerEntity player){world.setBlockState(pos,SceneMarkers.MARKER.getDefaultState());SceneMarkers.MARKER.onPlaced(world,pos,SceneMarkers.MARKER.getDefaultState(),player,new ItemStack(SceneMarkers.MARKER));}
    private String selected(ServerPlayerEntity player)throws Exception{var field=SceneMarkers.class.getDeclaredField("selected");field.setAccessible(true);return (String)((Map<?,?>)field.get(null)).get(player.getUuid());}
    private void command(ServerPlayerEntity player,String command)throws Exception{world.getServer().getCommandManager().getDispatcher().execute(command,player.getCommandSource());}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
