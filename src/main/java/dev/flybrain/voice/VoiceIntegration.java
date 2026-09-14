package dev.flybrain.voice;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import com.mojang.brigadier.arguments.StringArgumentType;

/** No Simple Voice Chat types here: the core mod loads even when the optional mod is absent. */
public final class VoiceIntegration {
    public static final Logger LOG = LoggerFactory.getLogger("flybrain/voice");
    public static volatile VoiceLibrary library;
    public static volatile MinecraftServer server;
    public static volatile BooleanSupplier available = () -> false;
    public static volatile BooleanSupplier recordingAllowed = () -> false;
    public static volatile Consumer<UUID> invalidate = id -> {};
    public static volatile Consumer<MinecraftServer> voiceTick = s -> {};
    public static volatile Consumer<Boolean> clientMute = muted -> {};
    private static final Map<UUID, Long> confirmations = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> receiving = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> distributionBlocked = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> shareRequests = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong shareGeneration = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor STORAGE = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30,
        java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32),
        r -> { var t = new Thread(r, "flybrain-voice-storage"); t.setDaemon(true); return t; }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private VoiceIntegration() {}
    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(VoiceMessage.ID, VoiceMessage.CODEC);
        PayloadTypeRegistry.playC2S().register(VoiceMessage.ID, VoiceMessage.CODEC);
        VoiceSync.initialize(); STORAGE.allowCoreThreadTimeOut(true);
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            var path = s.getSavePath(WorldSavePath.ROOT).resolve("flybrain-voices");
            STORAGE.execute(() -> {
                try { var loaded = new VoiceLibrary(path); if (server == s) library = loaded; }
                catch (IOException | RuntimeException e) { LOG.error("Voice library disabled: initialization failed", e); }
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            var current = library; if (current != null) current.stopAll();
            receiving.clear(); confirmations.clear(); distributionBlocked.clear(); shareRequests.clear(); VoiceSync.clear(); library = null; server = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> resetConnection(handler.player.getUuid()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> resetConnection(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(s -> { VoiceSync.tick(s); voiceTick.accept(s); });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(literal("flybrain")
            .then(literal("voice")
                .then(literal("start").executes(c -> start(c.getSource(), false))
                    .then(literal("confirm").executes(c -> start(c.getSource(), true))))
                .then(literal("stop").executes(c -> action(c.getSource(), "stop", "")))
                .then(literal("status").executes(c -> action(c.getSource(), "status", "")))
                .then(literal("list").executes(c -> action(c.getSource(), "list", "")))
                .then(literal("delete").then(argument("id", StringArgumentType.word())
                    .executes(c -> action(c.getSource(), "delete", StringArgumentType.getString(c, "id")))))
                .then(literal("clear").executes(c -> action(c.getSource(), "clear", "")))
                .then(literal("share").then(literal("on").executes(c -> action(c.getSource(), "share", "on")))
                    .then(literal("off").executes(c -> action(c.getSource(), "share", "off"))))
                .then(literal("receive").then(literal("on").executes(c -> action(c.getSource(), "receive", "on")))
                    .then(literal("off").executes(c -> action(c.getSource(), "receive", "off")))))));
    }
    public static boolean shared(VoiceLibrary.Clip clip) { var l = library; return l != null && !distributionBlocked.contains(clip.owner()) && l.shared(clip); }
    public static boolean receiving(UUID owner) { return receiving.getOrDefault(owner, false); }
    public static void resetConnection(UUID id) {
        var l = library; if (l != null) l.stop(id); confirmations.remove(id); receiving.remove(id); invalidate.accept(id); VoiceSync.reset(id);
    }
    public static void stopCapture(UUID id) { var l = library; if (l != null) l.stop(id); confirmations.remove(id); invalidate.accept(id); }
    public static void notify(UUID id, String text) {
        var s = server; if (s == null) return;
        s.execute(() -> { var player = s.getPlayerManager().getPlayer(id); if (player != null) player.sendMessage(Text.literal(text), false); });
    }
    private static int start(ServerCommandSource source, boolean confirm) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id = source.getPlayerOrThrow().getUuid(); var l = library;
        if (l == null || !available.getAsBoolean()) { source.sendError(Text.literal("Voice capture needs Simple Voice Chat 1.21.1-2.6.22 on server and client.")); return 0; }
        if (!recordingAllowed.getAsBoolean()) { source.sendError(Text.literal("Voice capture is off because server allow_recording is false.")); return 0; }
        long now = System.currentTimeMillis();
        if (!confirm) {
            confirmations.put(id, now + 60_000);
            source.sendFeedback(() -> Text.literal("SELF CONSENT: capture only YOUR transmitted proximity voice, excluding groups. Save 1-6 second PCM WAV clips (60 seconds total) on this server; the fly may replay distorted recognizable snippets later, even after capture stops. Capture starts OFF on each connection and expires after 30 minutes. Sharing is " + (l.sharing(id) ? "ON" : "OFF") + ": /flybrain voice share on permits redistribution into opted-in players' local server/world caches. /flybrain voice receive on separately permits hearing and caching shared voices. stop ends new capture; delete <id>/clear revoke saved clips and remove managed connected/reconnecting caches, not exported copies. To agree for YOUR voice, run /flybrain voice start confirm within 60 seconds."), false);
            return 1;
        }
        Long until = confirmations.remove(id);
        if (until == null || now >= until) { source.sendError(Text.literal("Run /flybrain voice start and read the consent message first.")); return 0; }
        if (l.full(id)) { source.sendError(Text.literal("Saved voice library reached its cap. Delete your clips or clear it before recording more; nothing was silently deleted.")); return 0; }
        invalidate.accept(id); l.start(id, now);
        source.sendFeedback(() -> Text.literal("Your voice capture is ON for this connection, at most 30 minutes. /flybrain voice stop ends capture."), false); return 1;
    }
    private static int action(ServerCommandSource source, String action, String value) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id = source.getPlayerOrThrow().getUuid(); var l = library;
        if (l == null) { source.sendError(Text.literal("Voice library is not active. Check server log for initialization errors.")); return 0; }
        if (action.equals("delete") || action.equals("clear") || action.equals("share")) {
            UUID clip;
            try { clip = action.equals("delete") ? UUID.fromString(value) : null; }
            catch (IllegalArgumentException e) { source.sendError(Text.literal("Clip id must be a UUID from your voice list.")); return 0; }
            if (action.equals("delete")) { stopCapture(id); l.revoke(id, clip); }
            if (action.equals("clear")) { stopCapture(id); l.revokeAll(id); }
            long shareRequest = shareGeneration.incrementAndGet();
            if (action.equals("share")) { shareRequests.put(id, shareRequest); distributionBlocked.add(id); }
            invalidate.accept(id);
            try { STORAGE.execute(() -> {
                try {
                    String message;
                    if (action.equals("delete")) message = l.delete(id, clip) ? "Your clip deleted and managed reuse revoked; exported copies elsewhere are not removed." : "No such clip in your own library.";
                    else if (action.equals("clear")) { l.clear(id); message = "Your library cleared. Managed caches reconcile before reuse; external exports are not removed."; }
                    else {
                        boolean on = value.equals("on"); l.share(id, on);
                        var activeServer = server;
                        if (on && activeServer != null) activeServer.execute(() -> {
                            if (server == activeServer && library == l && java.util.Objects.equals(shareRequests.get(id), shareRequest)) distributionBlocked.remove(id);
                        });
                        message = on ? "Your saved clips may now be redistributed to opted-in players' local caches and replayed by the fly." : "New distribution stopped. Managed caches reconcile; exported copies are not universally deleted.";
                    }
                    if (library == l) notify(id, message);
                } catch (IOException | RuntimeException e) { LOG.error("Voice storage command failed for {}", id, e); notify(id, "Voice storage action failed: " + e.getMessage()); }
            }); } catch (java.util.concurrent.RejectedExecutionException e) { source.sendError(Text.literal("Voice storage queue full. Revocations remain active; retry the storage action.")); return 0; }
            return 1;
        }
        try {
            switch (action) {
                case "stop" -> { stopCapture(id); source.sendFeedback(() -> Text.literal("Your voice capture stopped. Saved replay grants remain; delete/clear revokes them."), false); }
                case "status" -> source.sendFeedback(() -> Text.literal("Your capture=" + l.capturing(id, System.currentTimeMillis()) + ", saved=" + l.samples(id) / 48_000.0 + "/60 seconds, share=" + l.sharing(id) + ", receive=" + receiving(id) + ", voicechat=" + available.getAsBoolean() + ", allow_recording=" + recordingAllowed.getAsBoolean()), false);
                case "list" -> { var clips = l.list(id); source.sendFeedback(() -> Text.literal("Your saved clips: " + clips.size()), false); for (var clip : clips) source.sendFeedback(() -> Text.literal(clip.id() + " " + clip.samples() / 48_000.0 + "s"), false); }
                case "receive" -> { boolean on = value.equals("on"); receiving.put(id, on); VoiceSync.reset(id); source.sendFeedback(() -> Text.literal(on ? "You opted in to hearing fly voice replays and storing shared WAV clips in this server/world's local cache. /flybrain voice receive off ends this." : "Receiving and new caching stopped. Local managed cache will be cleared."), false); }
                default -> throw new IllegalArgumentException("Unknown voice action");
            }
            return 1;
        } catch (IllegalArgumentException e) { LOG.error("Voice command {} failed for {}", action, id, e); source.sendError(Text.literal("Voice action failed: " + e.getMessage())); return 0; }
    }
}




