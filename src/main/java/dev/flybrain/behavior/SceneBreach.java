package dev.flybrain.behavior;

import dev.flybrain.entity.CreatureEntity;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;

/** Scene-only, stone-pick-equivalent breach. Cancelled cracks never change blocks. */
final class SceneBreach {
    private final CreatureEntity mob;
    private final ItemStack pick=new ItemStack(Items.STONE_PICKAXE);
    private BlockPos position;
    private BlockState original;
    private int progress,required,broken;
    SceneBreach(CreatureEntity mob){this.mob=mob;}
    void reset(){cancel();broken=0;}
    void cancel(){if(position!=null&&mob.getWorld() instanceof ServerWorld world)world.setBlockBreakingInfo(mob.getId(),position,-1);position=null;progress=0;}
    private boolean allowed(ServerWorld world,BlockPos pos,ServerPlayerEntity witness){
        BlockState state=world.getBlockState(pos);
        int radius=world.getServer().getSpawnProtectionRadius();
        BlockPos spawn=world.getSpawnPos();
        boolean protectedSpawn=world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)&&radius>0&&Math.max(Math.abs(pos.getX()-spawn.getX()),Math.abs(pos.getZ()-spawn.getZ()))<=radius;
        return !protectedSpawn&&world.isChunkLoaded(pos)&&world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)
                &&!world.getServer().isSpawnProtected(world,pos,witness)
                &&state.getHardness(world,pos)>=0&&!state.hasBlockEntity()&&state.getFluidState().isEmpty()
                &&!state.isIn(BlockTags.PORTALS)&&!state.isIn(BlockTags.NEEDS_IRON_TOOL)&&!state.isIn(BlockTags.NEEDS_DIAMOND_TOOL)
                &&(!state.isToolRequired()||pick.isSuitableFor(state))&&PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world,witness,pos,state,null);
    }
    void tick(Vec3d evidence,ServerPlayerEntity witness){
        if(!(mob.getWorld() instanceof ServerWorld world)||witness==null||broken>=12){cancel();return;}
        BlockHitResult hit=world.raycast(new RaycastContext(mob.getEyePos(),evidence.add(0,1,0),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,mob));
        if(hit.getType()!=HitResult.Type.BLOCK||hit.getPos().squaredDistanceTo(mob.getEyePos())>9){cancel();return;}
        BlockPos next=hit.getBlockPos();
        if(position==null||!position.equals(next)){
            cancel();if(!allowed(world,next,witness))return;
            position=next.toImmutable();original=world.getBlockState(position);
            required=Math.max(1,(int)Math.ceil(original.getHardness(world,position)*30/pick.getMiningSpeedMultiplier(original)));
        }
        if(!original.equals(world.getBlockState(position))||!allowed(world,position,witness)){cancel();return;}
        progress++;if(progress%10==1)mob.swingHand(net.minecraft.util.Hand.MAIN_HAND);world.setBlockBreakingInfo(mob.getId(),position,Math.min(9,progress*10/required));
        if(progress%4==0)world.playSound(null,position,original.getSoundGroup().getHitSound(),net.minecraft.sound.SoundCategory.HOSTILE,.7f,.8f);
        mob.setBehavior("breach",false);
        if(progress>=required){if(world.breakBlock(position,true,mob))broken++;cancel();}
    }
}


