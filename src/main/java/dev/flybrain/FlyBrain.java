package dev.flybrain;

import com.google.gson.GsonBuilder;
import dev.flybrain.brain.BrainService;
import dev.flybrain.config.BrainConfig;
import dev.flybrain.entity.CreatureEntity;
import dev.flybrain.debug.DebugLines;
import dev.flybrain.debug.DebugSidebar;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.Comparator;
import java.util.LinkedHashMap;
import static net.minecraft.server.command.CommandManager.literal;

public final class FlyBrain implements ModInitializer {
    private static final com.google.gson.Gson JSON = new GsonBuilder().create();
    public static BrainConfig config;
    public static volatile BrainService brains;
    public static final EntityType<CreatureEntity> CREATURE = Registry.register(Registries.ENTITY_TYPE,
            Identifier.of("flybrain", "creature"), EntityType.Builder.create(CreatureEntity::new, SpawnGroup.CREATURE)
                    .dimensions(1.7f, 1.7f).maxTrackingRange(8).trackingTickInterval(1).build("flybrain:creature"));

    @Override public void onInitialize() {
        config = BrainConfig.load(FabricLoader.getInstance().getConfigDir().resolve("flybrain.json"));
        dev.flybrain.brain.BrainLifecycle.initialize();
        dev.flybrain.scene.SceneMarkers.initialize();
        dev.flybrain.audio.CreatureSounds.initialize();
        dev.flybrain.voice.VoiceIntegration.initialize();
        DebugSidebar.initialize();
        FabricDefaultAttributeRegistry.register(CREATURE, CreatureEntity.attributes());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> brains = new BrainService(config,
                FabricLoader.getInstance().getGameDir().resolve("logs/flybrain-" + System.currentTimeMillis() + ".jsonl")));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (brains != null) brains.close(); brains = null; });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
            literal("flybrain")
                .then(literal("spawn").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                    var source = context.getSource();
                    var mob = CREATURE.create(source.getWorld());
                    if (mob == null) throw new IllegalStateException("Creature construction failed");
                    var pos = source.getPosition();
                    double yaw = Math.toRadians(source.getRotation().y);
                    mob.refreshPositionAndAngles(pos.x - Math.sin(yaw) * 3, pos.y, pos.z + Math.cos(yaw) * 3,
                            source.getRotation().y + 180, 0);
                    if (!source.getWorld().spawnEntity(mob)) throw new IllegalStateException("Entity spawn rejected");
                    source.sendFeedback(() -> Text.literal("Spawned rigged GLB creature: " + mob.getUuid()), false);
                    return 1;
                }))
                .then(literal("debug").requires(source -> source.hasPermissionLevel(2)).executes(context -> debug(context.getSource())))
                .then(DebugSidebar.commands().requires(source -> source.hasPermissionLevel(2)))
                .then(DebugSidebar.watchCommand().requires(source -> source.hasPermissionLevel(2)))
                .then(literal("list").requires(source -> source.hasPermissionLevel(2)).executes(context -> DebugSidebar.list(context.getSource())))
                .then(literal("help").requires(source -> source.hasPermissionLevel(2)).executes(context -> DebugSidebar.help(context.getSource())))
                .then(literal("export").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                    var source = context.getSource();
                    var service = brains;
                    if (service == null || !service.record(JSON.toJson(snapshot(source)))) {
                        source.sendError(Text.literal("Export queue full or neural service stopped")); return 0;
                    }
                    source.sendFeedback(() -> Text.literal("JSON snapshot queued for " + service.sessionPath + "; /flybrain debug reports write failures"), false);
                    return 1;
                }))));
    }

    public static CreatureEntity nearest(ServerCommandSource source) {
        return source.getWorld().getEntitiesByType(CREATURE, e -> e.squaredDistanceTo(source.getPosition()) < 4096)
                .stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(source.getPosition()))).orElse(null);
    }
    private static java.util.Map<String, Object> snapshot(ServerCommandSource source) {
        return snapshot(source.getServer(), DebugSidebar.selected(source));
    }
    public static java.util.Map<String, Object> snapshot(net.minecraft.server.MinecraftServer server, CreatureEntity mob) {
        var values = new LinkedHashMap<String, Object>();
        var service = brains;
        var diagnostics = mob == null || service == null ? null : service.diagnostics(mob.getUuid());
        var result = diagnostics == null ? null : diagnostics.lastResult();
        long now = System.nanoTime();
        values.put("timestamp", java.time.Instant.now().toString());
        values.put("brainProcess",dev.flybrain.brain.BrainLifecycle.status());
        if(mob!=null){values.put("gameBehavior",mob.behaviorName());values.put("traversal",mob.visualMode());
            values.put("animationPaused",mob.animationPaused());values.put("growthScale",mob.growthScale());}
        values.put("model", "provided rigged GLB; 56 joints; 10 clips plus runtime feeding/surface animation");
        values.put("simulation", "Hybrid game AI with measured FlyWire neural modulation; freezes when neural output is stale");
        values.put("neuronsPerCreature", result == null ? null : result.output().neurons());
        values.put("connectionsPerCreature", result == null ? null : result.output().connections());
        values.put("brainHz", config.brainHz);
        values.put("sensoryHz", config.sensoryHz);
        values.put("sensoryRange", config.sensoryRange);
        values.put("timeoutMs", config.timeoutMs);
        values.put("externalPort", config.externalPort);
        values.put("maxBrains", config.maxBrains);
        values.put("speedGain", config.speed);
        values.put("turnGain", config.turnDegreesPerTick);
        values.put("jumpActivation", config.jumpActivation);
        values.put("serviceRunning", service != null);
        values.put("registered", diagnostics != null && diagnostics.registered());
        values.put("hasResult", result != null);
        values.put("resultAgeMs", result == null ? null : (now - result.submittedNanos()) / 1e6);
        values.put("inputAgeMs", diagnostics == null || diagnostics.latestSample() == null ? null : (now - diagnostics.latestSample().submittedNanos()) / 1e6);
        values.put("lastNeuralOutput", result == null ? dev.flybrain.brain.BrainOutput.STOP : result.output());
        values.put("computeMs", result == null ? null : result.output().computeNanos() / 1e6);
        values.put("activeBrains", service == null ? 0 : service.active());
        values.put("batchMs", service == null ? 0 : service.lastBatchNanos / 1e6);
        values.put("droppedLogs", service == null ? 0 : service.droppedLogs.get());
        values.put("messages", service == null ? 0 : service.messages.get());
        values.put("messagesPerSecond", service == null ? 0 : service.messages.get() * 1e9 / Math.max(1, System.nanoTime() - service.startedNanos));
        values.put("serverMspt", server.getAverageTickTime());
        values.put("estimatedTps", Math.min(20, 1000 / Math.max(.001, server.getAverageTickTime())));
        if (service != null) values.put("lastFailure", service.failure);
        if (mob != null) {
            values.put("entity", mob.getUuidAsString());
            values.put("position", new double[]{mob.getX(), mob.getY(), mob.getZ()});
            values.put("dimension", mob.getWorld().getRegistryKey().getValue().toString());
            values.put("alive", mob.isAlive()); values.put("noAi", mob.isAiDisabled());
            values.put("grounded", mob.isOnGround());
            values.put("horizontalCollision", mob.horizontalCollision); values.put("verticalCollision", mob.verticalCollision);
            values.put("x", mob.getX()); values.put("y", mob.getY()); values.put("z", mob.getZ());
            values.put("yaw", mob.getYaw()); values.put("health", mob.getHealth());
            values.put("velocityX", mob.getVelocity().x); values.put("velocityY", mob.getVelocity().y); values.put("velocityZ", mob.getVelocity().z);
            values.put("displacementBps", mob.debugDisplacementBps);
            values.put("jumpImpulse", mob.debugJumpImpulse);
            values.put("input", mob.lastInput); values.put("motorAndDrives", mob.lastOutput);
            values.put("hunger",mob.predator.drives.hunger());values.put("fear",mob.predator.drives.fear());
            values.put("curiosity",mob.predator.drives.curiosity());values.put("confidence",mob.predator.drives.confidence());
            values.put("sceneActive",mob.predator.sceneActive());values.put("feeding",mob.feeding());
        }
        if (result != null) values.put("latencyMs", (result.completedNanos() - result.submittedNanos()) / 1e6);
        values.put("status", DebugLines.state(values));
        values.put("diagnosis", DebugLines.reason(values));
        return values;
    }
    private static int debug(ServerCommandSource source) {
        if (!config.debug) { source.sendFeedback(() -> Text.literal("Debug disabled in config/flybrain.json"), false); return 0; }
        snapshot(source).forEach((key, value) -> source.sendFeedback(() -> Text.literal(key + ": " + JSON.toJson(value)), false));
        return 1;
    }

    public static void record(CreatureEntity mob) {
        var service = brains;
        if (service == null) return;
        var values = new LinkedHashMap<String, Object>();
        values.put("timestamp", java.time.Instant.now().toString());
        values.put("brainProcess",dev.flybrain.brain.BrainLifecycle.status());
        if(mob!=null){values.put("gameBehavior",mob.behaviorName());values.put("traversal",mob.visualMode());
            values.put("animationPaused",mob.animationPaused());values.put("growthScale",mob.growthScale());}
        values.put("entity", mob.getUuidAsString());
        values.put("position", new double[]{mob.getX(), mob.getY(), mob.getZ()});
        values.put("input", mob.lastInput); values.put("output", mob.lastOutput);
        service.record(JSON.toJson(values));
    }
}
