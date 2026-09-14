package dev.flybrain.scene;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.flybrain.FlyBrain;
import dev.flybrain.behavior.PredatorRules;
import dev.flybrain.debug.DebugSidebar;
import dev.flybrain.entity.CreatureEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.*;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import java.util.*;
import static net.minecraft.server.command.CommandManager.*;

/** Invisible solid-free marker; only subscribed players receive its overlay particles. */
public final class SceneMarkers {
    public static final Block MARKER=new Block(AbstractBlock.Settings.create().noCollision().nonOpaque().dropsNothing().strength(-1,3600000)) {
        @Override public BlockRenderType getRenderType(BlockState state){return BlockRenderType.INVISIBLE;}
        @Override public void onPlaced(World world,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack){
            super.onPlaced(world,pos,state,placer,stack);
            if(world instanceof ServerWorld server){
                State saved=state(server);String id=UUID.randomUUID().toString().substring(0,8);
                while(saved.markers.containsKey(id))id=UUID.randomUUID().toString().substring(0,8);
                saved.markers.put(id,new Marker(id,pos.toImmutable()));saved.markDirty();
                if(placer instanceof ServerPlayerEntity player){selected.put(player.getUuid(),id);player.sendMessage(Text.literal("Selected scene "+id),false);}
            }
        }
        @Override public void onStateReplaced(BlockState old,World world,BlockPos pos,BlockState next,boolean moved){
            if(!old.isOf(next.getBlock())&&world instanceof ServerWorld server){State saved=state(server);saved.markers.values().removeIf(marker->{if(!marker.pos.equals(pos))return false;stopActive(server,marker.id);return true;});saved.markDirty();}
            super.onStateReplaced(old,world,pos,next,moved);
        }
    };
    private static final Map<UUID,String> selected=new HashMap<>();
    private static final Set<UUID> shown=new HashSet<>();
    private static CreatureEntity active;
    private static String activeMarker;
    private SceneMarkers(){}
    public static void initialize(){
        Registry.register(Registries.BLOCK,Identifier.of("flybrain","scene_marker"),MARKER);
        Registry.register(Registries.ITEM,Identifier.of("flybrain","scene_marker"),new BlockItem(MARKER,new Item.Settings()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{active=null;activeMarker=null;selected.clear();shown.clear();});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(active!=null&&(!active.isAlive()||!active.predator.sceneActive())){active=null;activeMarker=null;}
            for(ServerWorld world:server.getWorlds())tick(world);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(literal("flybrain").requires(s->s.hasPermissionLevel(2))
            .then(literal("scene").requires(source->source.hasPermissionLevel(2))
                .then(literal("give").executes(c->{var p=c.getSource().getPlayerOrThrow();p.giveItemStack(new ItemStack(MARKER.asItem()));return 1;}))
                .then(literal("show").executes(c->{shown.add(c.getSource().getPlayerOrThrow().getUuid());return feedback(c.getSource(),"Scene overlays shown");}))
                .then(literal("hide").executes(c->{shown.remove(c.getSource().getPlayerOrThrow().getUuid());return feedback(c.getSource(),"Scene overlays hidden");}))
                .then(literal("list").executes(c->{state(c.getSource().getWorld()).markers.forEach((id,m)->c.getSource().sendFeedback(()->Text.literal(id+" "+m.pos.toShortString()+" "+(m.latch.spent()?"spent":"ready")).styled(style->style.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND,"/flybrain scene select "+id))),false));return 1;}))
                .then(literal("select").then(argument("id",StringArgumentType.word()).suggests((c,b)->{state(c.getSource().getWorld()).markers.keySet().forEach(b::suggest);return b.buildFuture();}).executes(c->{
                    String id=StringArgumentType.getString(c,"id");if(!state(c.getSource().getWorld()).markers.containsKey(id))return feedback(c.getSource(),"Scene not found");
                    selected.put(c.getSource().getPlayerOrThrow().getUuid(),id);return feedback(c.getSource(),"Selected scene "+id);
                })))
                .then(literal("trigger").executes(c->{var source=c.getSource();Marker m=chosen(source);if(m==null)return 0;CreatureEntity mob=nearbyFly(source.getWorld(),m,source);return start(source.getWorld(),m,source.getPlayerOrThrow(),mob)?feedback(source,"Scene started"):feedback(source,"Scene not started: active encounter, missing nearby fly, or neural pause");}))
                .then(literal("stop").executes(c->{Marker m=chosen(c.getSource());if(m==null)return 0;stopActive(c.getSource().getWorld(),m.id);return feedback(c.getSource(),"Selected scene stopped");}))
                .then(literal("reset").executes(c->{Marker m=chosen(c.getSource());if(m==null)return 0;m.gate=new PredatorRules.Reentry();m.latch.reset();state(c.getSource().getWorld()).markDirty();stopActive(c.getSource().getWorld(),m.id);return feedback(c.getSource(),"Scene reset; leave 8 blocks for 2 seconds before reentry");}))
                .then(literal("remove").executes(c->{var source=c.getSource();Marker m=chosen(source);if(m==null)return 0;stopActive(source.getWorld(),m.id);source.getWorld().removeBlock(m.pos,false);State s=state(source.getWorld());s.markers.remove(m.id);s.markDirty();return feedback(source,"Scene removed");})))
            .then(literal("growth").requires(source->source.hasPermissionLevel(2)).then(literal("reset").executes(c->resetCreature(c.getSource()))))
            .then(literal("creature").requires(source->source.hasPermissionLevel(2)).then(literal("reset").executes(c->resetCreature(c.getSource()))))));
    }
    private static void stopActive(ServerWorld world,String id){if(active!=null&&active.getWorld()==world&&id.equals(activeMarker)){active.predator.stop();active=null;activeMarker=null;}}
    private static int resetCreature(ServerCommandSource source){CreatureEntity mob=DebugSidebar.selected(source);if(mob==null)return 0;mob.resetGrowth();return feedback(source,"Watched fly growth reset");}
    private static int feedback(ServerCommandSource source,String text){source.sendFeedback(()->Text.literal(text),false);return 1;}
    private static Marker chosen(ServerCommandSource source)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Marker m=state(source.getWorld()).markers.get(selected.get(source.getPlayerOrThrow().getUuid()));
        if(m==null)m=state(source.getWorld()).markers.values().stream().filter(marker->marker.pos.toCenterPos().squaredDistanceTo(source.getPosition())<=1024).min(Comparator.comparingDouble(marker->marker.pos.toCenterPos().squaredDistanceTo(source.getPosition()))).orElse(null);
        if(m==null)source.sendError(Text.literal("No selected scene or marker within 32 blocks"));return m;
    }
    private static boolean start(ServerWorld world,Marker marker,ServerPlayerEntity player,CreatureEntity mob){
        if(marker.latch.spent()||active!=null||mob==null||!mob.isAlive()||mob.getWorld()!=world||mob.squaredDistanceTo(marker.pos.toCenterPos())>4096||mob.animationPaused())return false;
        marker.latch.start();state(world).markDirty();active=mob;activeMarker=marker.id;mob.predator.startScene(marker.pos.toCenterPos(),player);return true;
    }
    private static CreatureEntity nearbyFly(ServerWorld world,Marker marker,ServerCommandSource source){
        CreatureEntity watched=DebugSidebar.watchedOnly(source);
        if(watched!=null&&watched.isAlive()&&watched.getWorld()==world&&watched.squaredDistanceTo(marker.pos.toCenterPos())<=4096)return watched;
        return world.getEntitiesByType(FlyBrain.CREATURE,e->e.isAlive()&&e.squaredDistanceTo(marker.pos.toCenterPos())<=4096).stream()
                .min(Comparator.comparingDouble(e->e.squaredDistanceTo(marker.pos.toCenterPos()))).orElse(null);
    }
    private static void tick(ServerWorld world){
        List<ServerPlayerEntity> players=world.getPlayers();
        List<ServerPlayerEntity> eligible=players.stream().filter(p->!p.isSpectator()).toList();
        Map<UUID,Integer> overlays=new HashMap<>();
        for(Marker marker:state(world).markers.values()){
            if(!world.isChunkLoaded(marker.pos))continue;
            for(ServerPlayerEntity player:players){
                if(shown.contains(player.getUuid())&&world.getTime()%20==0&&player.squaredDistanceTo(marker.pos.toCenterPos())<4096&&overlays.getOrDefault(player.getUuid(),0)<8){
                    overlays.merge(player.getUuid(),1,Integer::sum);
                    world.spawnParticles(player,ParticleTypes.END_ROD,true,marker.pos.getX()+.5,marker.pos.getY()+.5,marker.pos.getZ()+.5,1,0,0,0,0);
                    for(int radius:new int[]{8,16,32})for(int i=0;i<8;i++){
                        double angle=i*Math.PI/4;
                        world.spawnParticles(player,ParticleTypes.END_ROD,true,marker.pos.getX()+.5+Math.cos(angle)*radius,marker.pos.getY()+.1,marker.pos.getZ()+.5+Math.sin(angle)*radius,1,0,0,0,0);
                    }
                }
            }
            if(active!=null||marker.latch.spent()||eligible.isEmpty())continue;
            ServerPlayerEntity observer=eligible.stream().min(Comparator.comparingDouble(p->p.squaredDistanceTo(marker.pos.toCenterPos()))).orElseThrow();
            CreatureEntity mob=nearbyFly(world,marker,observer.getCommandSource());
            if(mob==null||mob.animationPaused())continue;
            // One shared gate: every eligible player must stay outside before any reentry can trigger.
            if(marker.gate.update(observer.squaredDistanceTo(marker.pos.toCenterPos())<=64))start(world,marker,observer,mob);
        }
    }    private static final class Marker {
        final PredatorRules.SceneLatch latch=new PredatorRules.SceneLatch(false);
        final String id;final BlockPos pos;PredatorRules.Reentry gate=new PredatorRules.Reentry();
        Marker(String id,BlockPos pos){this.id=id;this.pos=pos;}
    }
    private static State state(ServerWorld world){return world.getPersistentStateManager().getOrCreate(new PersistentState.Type<>(State::new,State::read,null),"flybrain_scenes");}
    private static final class State extends PersistentState {
        final Map<String,Marker> markers=new LinkedHashMap<>();
        static State read(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){State state=new State();NbtList list=nbt.getList("markers",NbtElement.COMPOUND_TYPE);for(int i=0;i<list.size();i++){NbtCompound m=list.getCompound(i);String id=m.getString("id");Marker marker=new Marker(id,BlockPos.fromLong(m.getLong("pos")));if(m.getBoolean("spent"))marker.latch.start();state.markers.put(id,marker);}return state;}
        @Override public NbtCompound writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){NbtList list=new NbtList();markers.values().forEach(m->{NbtCompound entry=new NbtCompound();entry.putString("id",m.id);entry.putLong("pos",m.pos.asLong());entry.putBoolean("spent",m.latch.spent());list.add(entry);});nbt.put("markers",list);return nbt;}
    }
}







