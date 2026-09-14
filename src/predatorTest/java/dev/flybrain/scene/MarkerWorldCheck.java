package dev.flybrain.scene;
import com.mojang.authlib.GameProfile;
import dev.flybrain.entity.CreatureEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import java.util.UUID;
/** Server-world adapter test. Observer contexts are temporarily supplied, not connected clients. */
public final class MarkerWorldCheck {
 private MarkerWorldCheck(){}
 public static void run(ServerWorld world,CreatureEntity fly)throws Exception{
  var a=new ServerPlayerEntity(world.getServer(),world,new GameProfile(UUID.randomUUID(),"MarkerA"),SyncedClientOptions.createDefault());
  var b=new ServerPlayerEntity(world.getServer(),world,new GameProfile(UUID.randomUUID(),"MarkerB"),SyncedClientOptions.createDefault());
  var players=world.getPlayers();var tick=SceneMarkers.class.getDeclaredMethod("tick",ServerWorld.class);tick.setAccessible(true);
  players.add(a);players.add(b);
  try{
   a.refreshPositionAndAngles(32.5,102,20.5,0,0);b.refreshPositionAndAngles(20.5,102,20.5,0,0);
   for(int i=0;i<50;i++)tick.invoke(null,world);a.refreshPositionAndAngles(20.5,102,20.5,0,0);tick.invoke(null,world);
   require(!fly.predator.sceneActive(),"one player remaining inside prevents arming");
   a.refreshPositionAndAngles(32.5,102,20.5,0,0);b.refreshPositionAndAngles(8.5,102,20.5,0,0);for(int i=0;i<40;i++)tick.invoke(null,world);
   a.refreshPositionAndAngles(20.5,102,20.5,0,0);tick.invoke(null,world);require(fly.predator.sceneActive(),"all players outside40 ticks then reentry triggers");fly.predator.stop();
   var dispatcher=world.getServer().getCommandManager().getDispatcher();var source=world.getServer().getCommandSource().withLevel(0).withEntity(a);
   for(String command:new String[]{"flybrain scene list","flybrain growth reset","flybrain creature reset"}){
    require(dispatcher.parse(command,source).getReader().canRead(),"non-OP branch rejected: "+command);
   }
   Vec3d original=fly.getPos();
   var chosen=SceneMarkers.class.getDeclaredMethod("chosen",net.minecraft.server.command.ServerCommandSource.class);chosen.setAccessible(true);
   var nearbySource=world.getServer().getCommandSource().withEntity(a).withPosition(new Vec3d(20.5,101,20.5));
   Object nearest=chosen.invoke(null,nearbySource);require(nearest!=null,"unselected nearest marker within32");require(chosen.invoke(null,nearbySource.withPosition(new Vec3d(100,101,100)))==null,"unselected marker outside32 rejected");
   var lookup=SceneMarkers.class.getDeclaredMethod("nearbyFly",ServerWorld.class,nearest.getClass(),net.minecraft.server.command.ServerCommandSource.class);lookup.setAccessible(true);
   fly.refreshPositionAndAngles(-13.5,101,20.5,0,0);require(lookup.invoke(null,world,nearest,nearbySource)==fly,"nearest available fly between33 and64 is eligible");fly.refreshPositionAndAngles(original.x,original.y,original.z,0,0);
   fly.predator.startScene(original.add(40,0,0),null);require(fly.predator.routeAllowed(original),"64 block approach permits bound creature");
   fly.refreshPositionAndAngles(original.x+12,original.y,original.z,0,0);fly.predator.tick();require(!fly.predator.routeAllowed(original.add(73,0,0)),"32 block leash locks after approach");fly.predator.stop();fly.refreshPositionAndAngles(original.x,original.y,original.z,0,0);
   System.out.println("MARKER_WORLD_PASS all-player arming, non-OP command rejection, nearest marker32, fly64, approach then32 leash");
  }finally{players.remove(a);players.remove(b);fly.predator.stop();}
 }
 private static void require(boolean value,String reason){if(!value)throw new AssertionError("Marker boundary: "+reason);}
}

