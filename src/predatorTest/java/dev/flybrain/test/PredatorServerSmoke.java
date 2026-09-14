package dev.flybrain.test;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.flybrain.FlyBrain;
import dev.flybrain.entity.CreatureEntity;
import dev.flybrain.scene.SceneMarkers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Explicit transport fixture, NOT neural/FlyWire validation; excluded from the release jar. */
public final class PredatorServerSmoke implements ModInitializer {
    private HttpServer http;
    private volatile boolean online;
    private CreatureEntity fly;
    private PredatorPlayerSmoke playerSmoke;
    private PursuitWorldCheck pursuitSmoke;
    private ServerWorld world;
    private int tick,phase,startAge;
    private Vec3d frozen,goal;
    private boolean sawWall,sawAir,sawFeeding;
    private net.minecraft.entity.passive.PigEntity pig;
    private float pigInitialHealth;
    private final StringBuilder log=new StringBuilder("TRANSPORT FIXTURE ONLY, NOT FLYWIRE OR NEURAL EVIDENCE\n");
    @Override public void onInitialize(){
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            var player=handler.player;
            if(!java.util.Set.of("PredatorQA","GuestQA").contains(player.getGameProfile().getName()))return;
            server.getPlayerManager().addToOperators(player.getGameProfile());
            player.changeGameMode(phase>=9?net.minecraft.world.GameMode.CREATIVE:net.minecraft.world.GameMode.SPECTATOR);
            if(fly!=null)player.teleport(world,fly.getX()+6,fly.getY()+2,fly.getZ()+6,135,10);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{if(http!=null)http.stop(0);});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(fly!=null)try{tick(server);}catch(Throwable error){log.append("FAIL ").append(error).append('\n');save();fly=null;throw new IllegalStateException("Predator fixture failed",error);}});
    }
    private void start(MinecraftServer server){
        try{
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",18765),0);
            http.createContext("/step",exchange->{
                if(!online){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
                JsonObject request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject outputs=new JsonObject();request.getAsJsonObject("samples").entrySet().forEach(entry->{
                    JsonObject out=new JsonObject();out.add("sequence",entry.getValue().getAsJsonObject().get("sequence"));
                    for(String key:new String[]{"forward","turn","ascend","forwardHz","backwardHz","leftHz","rightHz","escapeHz","computeNanos"})out.addProperty(key,0);
                    out.addProperty("neurons",1);out.addProperty("connections",1);outputs.add(entry.getKey(),out);
                });
                JsonObject response=new JsonObject();response.addProperty("protocol",1);response.addProperty("model","shiu-brian2-fafb783");response.add("outputs",outputs);
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });http.start();
            world=server.getOverworld();world.setTimeOfDay(6000);
            for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++)world.setChunkForced(x,z,true);
            for(int x=-8;x<=44;x++)for(int z=-12;z<=36;z++){
                world.getChunk(x>>4,z>>4);world.setBlockState(new BlockPos(x,100,z),Blocks.STONE.getDefaultState());
                for(int y=101;y<=114;y++)world.setBlockState(new BlockPos(x,y,z),Blocks.AIR.getDefaultState());
            }
            fly=FlyBrain.CREATURE.create(world);require(fly!=null,"entity construction");fly.refreshPositionAndAngles(.5,101,.5,0,0);require(world.spawnEntity(fly),"entity spawn");
            fly.setCustomName(net.minecraft.text.Text.literal("PREDATOR_QA_ACTIVE"));fly.setCustomNameVisible(false);fly.addCommandTag("predator_fixture_active");playerSmoke=new PredatorPlayerSmoke(fly,world,value->online=value,this::check);
            frozen=fly.getPos();check("spawn construction and tracked-data defaults UUID="+fly.getUuid());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private void tick(MinecraftServer server)throws Exception{
        tick++;
        if(tick==1&&phase==0)world.getEntitiesByType(FlyBrain.CREATURE,e->e!=fly).forEach(CreatureEntity::discard);
        if(tick%100==0)System.out.println("PREDATOR_TRACE phase="+phase+" visualAge="+fly.visualAge()+" paused="+fly.animationPaused()+" position="+fly.getPos()+" mode="+fly.visualMode()+" brain="+(FlyBrain.brains==null?"null":FlyBrain.brains.failure));
        feedingCheck();
        if(fly.age<100&&tick%10==0){world.getEntitiesByType(FlyBrain.CREATURE,e->e!=fly).forEach(CreatureEntity::discard);world.getEntitiesByType(EntityType.PIG,e->true).forEach(net.minecraft.entity.Entity::discard); }
        if(phase==9){if(pursuitSmoke==null)pursuitSmoke=new PursuitWorldCheck(world,fly);if(pursuitSmoke.tick()){check("sustained failed pursuit permits first breach only after 5 seconds");phase=10;}}
        if(phase==10)playerSmoke.tick();
        require(world.isSpaceEmpty(fly,fly.getBoundingBox().contract(.005)),"actual bounding box overlaps block at "+fly.getPos());
        if(phase==0&&tick>=30){
            require(fly.animationPaused()&&fly.visualAge()==0&&fly.getPos().squaredDistanceTo(frozen)<1e-12,"offline freeze");
            fly.addBlood(24);require(fly.growthScale()==1.25f,"growth threshold");
            NbtCompound nbt=new NbtCompound();fly.writeCustomDataToNbt(nbt);fly.resetGrowth();fly.readCustomDataFromNbt(nbt);require(fly.growthScale()==1.25f,"growth NBT roundtrip");fly.resetGrowth();
            check("offline physics/visual freeze and blood/growth NBT roundtrip");online=true;phase=1;
        }
        if(phase==1&&!fly.animationPaused()){
            check("two distinct transport responses resume actual entity");
            BlockPos marker=new BlockPos(20,101,20);world.setBlockState(marker,SceneMarkers.MARKER.getDefaultState());SceneMarkers.MARKER.onPlaced(world,marker,SceneMarkers.MARKER.getDefaultState(),null,new net.minecraft.item.ItemStack(SceneMarkers.MARKER));
            require(SceneMarkers.MARKER.getDefaultState().getCollisionShape(world,marker).isEmpty(),"marker noncollision");
            fly.predator.startScene(fly.getPos(),null);require(fly.predator.sceneActive(),"scene starts");fly.predator.stop();require(!fly.predator.sceneActive(),"scene stops");
            check("real marker placement/noncollision and scene start/stop");
            dev.flybrain.scene.MarkerWorldCheck.run(world,fly);check("headless marker observer/permission/approach boundaries");
            dev.flybrain.behavior.BreachWorldCheck.run(world,fly);check("live breach boundary/timing checks");
            for(int x=5;x<=13;x++)for(int z=-2;z<=3;z++)world.setBlockState(new BlockPos(x,101,z),Blocks.STONE.getDefaultState());
            begin(2,new Vec3d(10.5,102,.5));
        }
        if(phase>=2&&phase<=4){
            fly.predatorNavigation.toward(goal);sawWall|=fly.visualMode().equals("wall");sawAir|=fly.visualMode().equals("air");
            if(fly.squaredDistanceTo(goal)<.25){
                if(phase==2){require(sawAir,"short ascent used flight");check("collision-free short-ledge navigation");fly.refreshPositionAndAngles(.5,101,8.5,0,0);fly.setVelocity(Vec3d.ZERO);fly.predatorNavigation.clear();for(int x=-3;x<=14;x++)for(int y=101;y<=110;y++)world.setBlockState(new BlockPos(x,y,10),Blocks.STONE.getDefaultState());begin(3,new Vec3d(8.5,107,8.5));}
                else if(phase==3){require(sawWall,"continuous tall wall used wall mode");check("continuous tall-wall ascent using real bounding box");fly.refreshPositionAndAngles(.5,101,24.5,0,0);fly.setVelocity(Vec3d.ZERO);fly.predatorNavigation.clear();for(int y=101;y<=105;y++){for(int z=20;z<=28;z++)world.setBlockState(new BlockPos(4,y,z),Blocks.STONE.getDefaultState());for(int z=24;z<=32;z++)world.setBlockState(new BlockPos(8,y,z),Blocks.STONE.getDefaultState());}begin(4,new Vec3d(13.5,101,24.5));}
                else {check("blocked direct route replanned around maze blocks without overlap");online=false;phase=5;tick=0;}
            }
            require(fly.visualAge()-startAge<600,"navigation timeout phase "+phase+" position "+fly.getPos()+" mode "+fly.visualMode());
        }
        if(phase==5&&fly.animationPaused()){frozen=fly.getPos();startAge=fly.visualAge();phase=6;tick=0;}
        if(phase==6&&tick>=30){require(fly.getPos().squaredDistanceTo(frozen)<1e-12&&fly.visualAge()==startAge,"mid-flight stale freeze");check("post-navigation stale physics/visual freeze");online=true;phase=7;}
    }
    private void feedingCheck(){
        if(phase==7&&!fly.animationPaused()){
            fly.predator.stop();fly.refreshPositionAndAngles(30.5,103,2.5,180,0);fly.setVelocity(Vec3d.ZERO);
            pig=EntityType.PIG.create(world);require(pig!=null,"pig construct");pig.refreshPositionAndAngles(30.5,103,.5,0,0);pigInitialHealth=pig.getHealth();pig.setAiDisabled(true);pig.setNoGravity(true);world.spawnEntity(pig);
            fly.predator.startScene(fly.getPos(),pig);phase=8;startAge=fly.visualAge();
        }
        if(phase==8){
            sawFeeding|=fly.feeding();
            if(sawFeeding&&!fly.feeding()){
                require(Math.abs(pigInitialHealth-pig.getHealth()-6)<.01,"actual feeding health delta was "+(pigInitialHealth-pig.getHealth()));
                NbtCompound saved=new NbtCompound();fly.writeCustomDataToNbt(saved);require(Math.abs(saved.getFloat("FlyBrainBlood")-6)<.01,"actual health loss credited as blood");
                fly.predator.stop();pig.discard();phase=9;world.getPlayers().forEach(player->{if(java.util.Set.of("PredatorQA","GuestQA").contains(player.getGameProfile().getName()))player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);});check("actual airborne bite/feed schedule deals 6 HP and credits 6 blood");check("SERVER_SMOKE_PASS (transport-only); server remains for client QA");
            }
            require(fly.visualAge()-startAge<240,"feeding timeout");
        }
    }
    private void begin(int next,Vec3d destination){phase=next;goal=destination;startAge=fly.visualAge();sawAir=false;sawWall=false;}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void check(String message){log.append("PASS ").append(message).append('\n');System.out.println("PREDATOR_SMOKE "+message);save();}
    private void save(){try{Files.writeString(Path.of("server-smoke.txt"),log.toString());}catch(Exception e){throw new IllegalStateException(e);}}
}










