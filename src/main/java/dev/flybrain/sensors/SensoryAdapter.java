package dev.flybrain.sensors;

import dev.flybrain.brain.BrainInput;
import dev.flybrain.entity.CreatureEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class SensoryAdapter {
    private SensoryAdapter() {}
    public static BrainInput sample(CreatureEntity mob, long sequence, double range) {
        double yaw = Math.toRadians(mob.getYaw());
        double left = 0, right = 0;
        int seen = 0;
        for (var other : mob.getWorld().getOtherEntities(mob, mob.getBoundingBox().expand(range),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            if (++seen > 16) break;
            Vec3d delta = other.getPos().subtract(mob.getPos());
            double distance = delta.length();
            if (distance < .001 || distance > range || !mob.canSee(other)) continue;
            double facing = (-Math.sin(yaw) * delta.x + Math.cos(yaw) * delta.z) / distance;
            if (facing < .5) continue; // 120-degree visual cone
            double bearing = (-Math.cos(yaw) * delta.x - Math.sin(yaw) * delta.z) / distance;
            double signal = (1 - distance / range) * facing;
            left = Math.max(left, signal * (1 - bearing) * .5);
            right = Math.max(right, signal * (1 + bearing) * .5);
        }
        return new BrainInput(sequence, left, right, obstacle(mob, yaw - Math.PI / 4),
                obstacle(mob, yaw + Math.PI / 4), mob.getWorld().getLightLevel(mob.getBlockPos()) / 15.0,
                mob.hurtTime > 0 ? 1 : 0, mob.isOnGround());
    }
    private static double obstacle(CreatureEntity mob, double yaw) {
        Vec3d start = mob.getPos().add(0, .5, 0);
        Vec3d end = start.add(-Math.sin(yaw) * 1.5, 0, Math.cos(yaw) * 1.5);
        var hit = mob.getWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mob));
        return hit.getType() == HitResult.Type.MISS ? 0 : 1 - start.distanceTo(hit.getPos()) / 1.5;
    }
}
