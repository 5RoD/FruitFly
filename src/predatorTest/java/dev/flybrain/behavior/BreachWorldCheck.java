package dev.flybrain.behavior;

import com.mojang.authlib.GameProfile;
import dev.flybrain.entity.CreatureEntity;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live block-world boundary tests using an unconnected player permission context, not a fake client. */
public final class BreachWorldCheck {
    private BreachWorldCheck(){}
    public static void run(ServerWorld world,CreatureEntity fly){
        Vec3d original=fly.getPos();float yaw=fly.getYaw();BlockPos pos=new BlockPos(32,104,2);
        var old=world.getBlockState(pos);var oldSpawn=world.getSpawnPos();float oldAngle=world.getSpawnAngle();boolean griefing=world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
        var veto=new AtomicBoolean();
        PlayerBlockBreakEvents.BEFORE.register((w,p,where,state,entity)->!(w==world&&where.equals(pos)&&veto.get()));
        var witness=new ServerPlayerEntity(world.getServer(),world,new GameProfile(UUID.randomUUID(),"BreachBoundaryQA"),SyncedClientOptions.createDefault());
        var breach=new SceneBreach(fly);Vec3d evidence=new Vec3d(35,103,2.5);
        try{
            fly.refreshPositionAndAngles(30.5,103,2.5,-90,0);fly.setVelocity(Vec3d.ZERO);
            world.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(true,world.getServer());
                        world.getServer().getPlayerManager().addToOperators(witness.getGameProfile());world.setSpawnPos(pos,0);
            world.setBlockState(pos,Blocks.STONE.getDefaultState());advance(breach,evidence,witness,12);require(world.getBlockState(pos).isOf(Blocks.STONE),"spawn protection also applies to OP witness");
            world.setSpawnPos(oldSpawn,oldAngle);world.getServer().getPlayerManager().removeFromOperators(witness.getGameProfile());breach.reset();
            world.setBlockState(pos,Blocks.STONE.getDefaultState());veto.set(true);advance(breach,evidence,witness,30);require(world.getBlockState(pos).isOf(Blocks.STONE),"Fabric BEFORE veto");
            veto.set(false);world.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(false,world.getServer());advance(breach,evidence,witness,30);require(world.getBlockState(pos).isOf(Blocks.STONE),"mobGriefing false");
            world.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(true,world.getServer());
            for(var block:new net.minecraft.block.Block[]{Blocks.CHEST,Blocks.OBSIDIAN,Blocks.BEDROCK,Blocks.DIAMOND_ORE,Blocks.WATER}){
                world.setBlockState(pos,block.getDefaultState());advance(breach,evidence,witness,100);require(world.getBlockState(pos).isOf(block),"excluded "+block);breach.cancel();
            }
            world.setBlockState(pos,Blocks.STONE.getDefaultState());advance(breach,evidence,witness,5);breach.cancel();require(world.getBlockState(pos).isOf(Blocks.STONE),"cancel pending cracks preserves block");
            advance(breach,evidence,witness,10);require(world.getBlockState(pos).isOf(Blocks.STONE),"stone cannot break before 12 pick ticks");advance(breach,evidence,witness,2);require(world.isAir(pos),"stone pick timing completes at 12 ticks");
            breach.reset();for(int i=0;i<12;i++){world.setBlockState(pos,Blocks.STONE.getDefaultState());advance(breach,evidence,witness,12);require(world.isAir(pos),"allowed breach "+i);}
            world.setBlockState(pos,Blocks.STONE.getDefaultState());advance(breach,evidence,witness,50);require(world.getBlockState(pos).isOf(Blocks.STONE),"hard 12 block encounter cap");
            breach.reset();world.setBlockState(pos,Blocks.DIRT.getDefaultState());advance(breach,evidence,witness,15);require(world.isAir(pos),"stone pick can break non-tool-required dirt");
            System.out.println("BREACH_WORLD_PASS veto, mobGriefing, excluded blocks, crack cancel, stone timing, 12 block cap and dirt");
        }finally{world.setSpawnPos(oldSpawn,oldAngle);world.getServer().getPlayerManager().removeFromOperators(witness.getGameProfile());veto.set(false);breach.cancel();world.setBlockState(pos,old);world.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(griefing,world.getServer());fly.refreshPositionAndAngles(original.x,original.y,original.z,yaw,0);fly.setVelocity(Vec3d.ZERO);}
    }
    private static void advance(SceneBreach breach,Vec3d evidence,ServerPlayerEntity witness,int ticks){for(int i=0;i<ticks;i++)breach.tick(evidence,witness);}
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError("Breach boundary: "+reason);}
}

