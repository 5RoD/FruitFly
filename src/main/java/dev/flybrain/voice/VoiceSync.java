package dev.flybrain.voice;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Server-authoritative downloads only. Clients never upload audio, owners, or file paths. */
public final class VoiceSync {
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final AtomicLong generations = new AtomicLong();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { var t = new Thread(r, "flybrain-voice-download"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private static final class Session {
        long generation = generations.incrementAndGet(), revision = -1, lastRequest;
        boolean receive, ready;
        final ArrayDeque<VoiceMessage> pending = new ArrayDeque<>();
        final Map<UUID, VoiceLibrary.Clip> known = new HashMap<>();
        VoiceLibrary.Clip clip;
        CompletableFuture<byte[]> load;
        byte[] bytes;
        int offset;
    }
    private VoiceSync() {}
    public static void initialize() {
        IO.allowCoreThreadTimeOut(true);
        ServerPlayNetworking.registerGlobalReceiver(VoiceMessage.ID, (message, context) -> {
            if (message.kind() != VoiceMessage.REQUEST && message.kind() != VoiceMessage.MUTE) return;
            context.server().execute(() -> request(context.player().getUuid(), message));
        });
    }
    /** Authenticated sender identity is supplied by Fabric, never by a field in the payload. */
    static void request(UUID id, VoiceMessage message) {
        if (message.kind() == VoiceMessage.MUTE) { VoiceIntegration.stopCapture(id); return; }
        if (message.kind() != VoiceMessage.REQUEST) return;
        var state = sessions.get(id); var library = VoiceIntegration.library;
        if (state == null || library == null || !state.ready || !VoiceIntegration.receiving(id)
                || message.generation() != state.generation || state.clip != null) return;
        long now = System.currentTimeMillis(); if (now - state.lastRequest < 200) return; state.lastRequest = now;
        var clip = library.get(message.clip());
        if (clip == null || !allowed(id, clip, library)) return;
        state.clip = clip;
        try {
            state.load = CompletableFuture.supplyAsync(() -> {
                try { return library.read(clip); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }, IO);
        } catch (java.util.concurrent.RejectedExecutionException e) { state.clip = null; VoiceIntegration.notify(id, "Voice download queue is busy; toggle receive off/on to retry."); }
    }
    public static void reset(UUID id) {
        var s = VoiceIntegration.server;
        if (s != null && !s.isOnThread()) { s.execute(() -> reset(id)); return; }
        var state = sessions.remove(id); if (state != null && state.load != null) state.load.cancel(false);
    }
    public static void clear() { for (var state : sessions.values()) if (state.load != null) state.load.cancel(false); sessions.clear(); }
    private static boolean allowed(UUID receiver, VoiceLibrary.Clip clip, VoiceLibrary library) {
        return VoiceIntegration.receiving(receiver) && library.reusable(clip) && (receiver.equals(clip.owner()) || VoiceIntegration.shared(clip));
    }
    public static void tick(MinecraftServer server) {
        var library = VoiceIntegration.library; if (library == null) return;
        int budget = 8; // 128 KiB/tick globally; one 16 KiB packet per receiver/tick.
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!ServerPlayNetworking.canSend(player, VoiceMessage.ID)) continue;
            UUID id = player.getUuid(); var state = sessions.computeIfAbsent(id, unused -> new Session());
            boolean receive = VoiceIntegration.receiving(id);
            if (state.revision != library.revision() || state.receive != receive) snapshot(state, id, library, receive);
            if (budget <= 0) continue;
            if (!state.pending.isEmpty()) {
                var message = state.pending.removeFirst(); ServerPlayNetworking.send(player, message); budget--;
                if (message.kind() == VoiceMessage.MANIFEST_END) state.ready = true;
                continue;
            }
            if (state.clip == null) continue;
            if (!allowed(id, state.clip, library)) { state.clip = null; state.bytes = null; continue; }
            if (state.bytes == null && state.load != null && state.load.isDone()) {
                try { state.bytes = state.load.join(); }
                catch (RuntimeException e) { VoiceIntegration.LOG.error("Voice clip download failed", e); VoiceIntegration.notify(id, "Voice clip download failed; check server log."); state.clip = null; continue; }
                state.load = null;
            }
            if (state.bytes == null) continue;
            var clip = state.clip;
            if (state.offset < state.bytes.length) {
                var data = Arrays.copyOfRange(state.bytes, state.offset, Math.min(state.offset + VoiceAudio.CHUNK_BYTES, state.bytes.length));
                ServerPlayNetworking.send(player, new VoiceMessage(VoiceMessage.CHUNK, clip.owner(), clip.id(), state.generation, state.offset, state.bytes.length, clip.hash(), data));
                state.offset += data.length;
            } else {
                ServerPlayNetworking.send(player, new VoiceMessage(VoiceMessage.COMPLETE, clip.owner(), clip.id(), state.generation, state.offset, state.bytes.length, clip.hash(), new byte[0]));
                state.clip = null; state.bytes = null; state.offset = 0;
            }
            budget--;
        }
    }
    private static void snapshot(Session state, UUID receiver, VoiceLibrary library, boolean receive) {
        state.pending.clear(); if (state.load != null) state.load.cancel(false); state.load = null; state.bytes = null; state.clip = null; state.offset = 0;
        state.generation = generations.incrementAndGet(); state.revision = library.revision(); state.receive = receive; state.ready = false;
        state.pending.add(new VoiceMessage(VoiceMessage.STATE, VoiceMessage.NONE, VoiceMessage.NONE, state.generation, 0, receive ? 1 : 0, "", new byte[0]));
        state.pending.add(new VoiceMessage(VoiceMessage.RESET, library.serverId, VoiceMessage.NONE, state.generation, 0, 0, "", new byte[0]));
        var current = new HashMap<UUID, VoiceLibrary.Clip>();
        if (receive) for (var clip : library.all()) if (allowed(receiver, clip, library)) current.put(clip.id(), clip);
        for (var removed : state.known.values()) if (!current.containsKey(removed.id()))
            state.pending.add(new VoiceMessage(VoiceMessage.TOMBSTONE, removed.owner(), removed.id(), state.generation, 0, 0, "", new byte[0]));
        state.known.clear(); state.known.putAll(current);
        for (var clip : current.values())
            state.pending.add(new VoiceMessage(VoiceMessage.MANIFEST, clip.owner(), clip.id(), state.generation, 0, 44 + clip.samples() * 2, clip.hash(), new byte[0]));
        state.pending.add(VoiceMessage.control(VoiceMessage.MANIFEST_END, state.generation));
    }
}



