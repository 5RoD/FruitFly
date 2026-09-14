package dev.flybrain.movement;

import dev.flybrain.brain.BrainOutput;
import dev.flybrain.config.BrainConfig;
import dev.flybrain.entity.CreatureEntity;
import net.minecraft.util.math.MathHelper;

public final class MotorController {
    private MotorController() {}
    public static void apply(CreatureEntity mob, BrainOutput motor, BrainConfig config) {
        float yaw = MathHelper.wrapDegrees(mob.getYaw() + (float)(motor.turn() * config.turnDegreesPerTick));
        mob.setYaw(yaw); mob.setHeadYaw(yaw); mob.setBodyYaw(yaw);
        double angle = Math.toRadians(yaw), speed = motor.forward() * config.speed;
        double vertical = mob.getVelocity().y;
        // Saved assay: visual input 12-46Hz escape; obstacle input190-198Hz. Avoid weak-activity micro-hops.
        mob.debugJumpImpulse = mob.escapeImpulse.update(motor.ascend(),mob.isOnGround(),config.jumpActivation);
        if (mob.debugJumpImpulse > 0) vertical = mob.debugJumpImpulse;
        mob.setVelocity(-Math.sin(angle) * speed, vertical, Math.cos(angle) * speed);
        // Vanilla physics owns gravity, collision and stepping. No pathfinding or pose animation here.
    }
}
