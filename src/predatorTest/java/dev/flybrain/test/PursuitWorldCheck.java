package dev.flybrain.test;
import com.mojang.authlib.GameProfile;
import dev.flybrain.entity.CreatureEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;
/** Real simulation ticks and real pig/blocks exercise pursuit-to-breach timing. */
final class PursuitWorldCheck {
 private final ServerWorld world;private final CreatureEntity fly;private PigEntity pig;private int stage,closedAt,started;
 private final BlockPos opening=new BlockPos(33,102,2);
 PursuitWorldCheck(ServerWorld world,CreatureEntity fly){this.world=world;this.fly=fly;}
 boolean tick(){
  if(stage==0){
   fly.predator.stop();fly.refreshPositionAndAngles(30.5,101,2.5,-90,0);fly.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
   pig=EntityType.PIG.create(world);if(pig==null)throw new AssertionError("pursuit pig creation");pig.refreshPositionAndAngles(39.5,101,2.5,0,0);pig.setAiDisabled(true);pig.setNoGravity(true);world.spawnEntity(pig);
   var actor=new ServerPlayerEntity(world.getServer(),world,new GameProfile(UUID.randomUUID(),"PursuitContextQA"),SyncedClientOptions.createDefault());actor.refreshPositionAndAngles(60.5,101,2.5,0,0);
   fly.predator.startScene(fly.getPos().add(3,0,0),actor);started=fly.visualAge();stage=1;
  }
  if(stage==1&&fly.behaviorName().equals("pursue")){
   chamber(true);closedAt=fly.visualAge();stage=2;System.out.println("PURSUIT_ROOM_CLOSED worldTick="+world.getTime()+" visualAge="+closedAt);
  }
  if(stage==2&&world.isAir(opening)){
   int elapsed=fly.visualAge()-closedAt;if(elapsed<100)throw new AssertionError("premature breach after "+elapsed+" ticks");
   System.out.println("PURSUIT_BREACH_PASS first opening after "+elapsed+" actual simulation ticks; worldTick="+world.getTime());
   fly.predator.stop();pig.discard();chamber(false);stage=3;return true;
  }
  if(fly.visualAge()-started>400)throw new AssertionError("pursuit breach timeout stage="+stage+" behavior="+fly.behaviorName()+" pos="+fly.getPos());
  return stage==3;
 }
 private void chamber(boolean close){
  for(int x=27;x<=33;x++)for(int z=-1;z<=5;z++)for(int y=101;y<=104;y++)if(x==27||x==33||z==-1||z==5||y==104)world.setBlockState(new BlockPos(x,y,z),(close?Blocks.STONE:Blocks.AIR).getDefaultState());
 }
}
