package dev.flybrain.voice;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** No microphone device access. All received files are authorized server clips, never local mic buffers. */
public final class VoiceClient {
    private static final AtomicLong connection = new AtomicLong();
    private static final VoiceReceiveConsent consent = new VoiceReceiveConsent();
    private static UUID serverIdentity;
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), r -> { var t = new Thread(r, "flybrain-voice-cache"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    // Accessed only by the single cache worker; a connection token invalidates pending jobs on disconnect.
    private static long generation = -1;
    private static boolean receive, manifestReady;
    private static VoiceCache cache;
    private static final Map<UUID, VoiceCache.Entry> manifest = new HashMap<>();
    private static final ArrayDeque<VoiceCache.Entry> missing = new ArrayDeque<>();
    private static VoiceCache.Entry active;
    private static VoiceAudio.Transfer transfer;
    private VoiceClient() {}
    public static void initialize() {
        IO.allowCoreThreadTimeOut(true);
        ClientSendMessageEvents.COMMAND.register(command -> {
            consent.command(command);
            if (!consent.acceptServerState(true)) {
                consent.disconnect(); connection.incrementAndGet(); IO.getQueue().clear();
                try { IO.execute(() -> {
                    try { if (cache != null) cache.reconcile(java.util.List.of()); }
                    catch (java.io.IOException e) { VoiceIntegration.LOG.error("Voice cache removal failed", e); }
                    clearState();
                }); } catch (java.util.concurrent.RejectedExecutionException e) { VoiceIntegration.LOG.error("Voice cache revocation queue rejected", e); }
            }
        });
        ClientPlayConnectionEvents.INIT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        VoiceIntegration.clientMute = muted -> {
            if (!muted) return;
            MinecraftClient.getInstance().execute(() -> {
                if (ClientPlayNetworking.canSend(VoiceMessage.ID)) ClientPlayNetworking.send(VoiceMessage.control(VoiceMessage.MUTE, 0));
            });
        };
        ClientPlayNetworking.registerGlobalReceiver(VoiceMessage.ID, (message, context) -> {
            long token = connection.get();
            String host = context.client().getCurrentServerEntry() == null ? "local-world" : context.client().getCurrentServerEntry().address;
            try { IO.execute(() -> {
                if (token != connection.get()) return;
                try { accept(message, host, token); }
                catch (Exception e) {
                    clearState(); VoiceIntegration.LOG.error("Voice cache rejected transfer; toggle receive off/on to retry", e);
                    context.client().execute(() -> { if (context.client().player != null) context.client().player.sendMessage(net.minecraft.text.Text.literal("Voice cache stopped: " + e.getMessage() + ". No old clips were silently deleted."), false); });
                }
            }); } catch (java.util.concurrent.RejectedExecutionException e) { reset(); VoiceIntegration.LOG.error("Voice cache queue full; pending transfers cancelled", e); }
        });
    }
    private static void reset() {
        consent.disconnect(); connection.incrementAndGet(); IO.getQueue().clear();
        try { IO.execute(() -> { clearState(); serverIdentity = null; }); } catch (java.util.concurrent.RejectedExecutionException e) { VoiceIntegration.LOG.error("Voice cache reset queue rejected", e); }
    }
    private static void clearState() { receive = false; manifestReady = false; generation = -1; cache = null; manifest.clear(); missing.clear(); active = null; transfer = null; }
    private static void accept(VoiceMessage message, String host, long token) throws java.io.IOException {
        if (message.kind() == VoiceMessage.STATE) {
            if (message.total() > 1) throw new IllegalArgumentException("Invalid receive state");
            receive = consent.acceptServerState(message.total() == 1); generation = message.generation(); manifestReady = false; return;
        }
        if (message.generation() != generation || !consent.acceptServerState(true)) return;
        switch (message.kind()) {
            case VoiceMessage.RESET -> {
                if (!receive) return;
                if (serverIdentity != null && !serverIdentity.equals(message.owner())) throw new IllegalArgumentException("Server changed cache identity during connection");
                serverIdentity = message.owner();
                String namespace = VoiceAudio.hash((host + "\n" + message.owner()).getBytes(StandardCharsets.UTF_8));
                var base = FabricLoader.getInstance().getGameDir().resolve("flybrain-voice-cache");
                cache = new VoiceCache(base.resolve(namespace), base);
                manifest.clear(); missing.clear(); active = null; transfer = null; manifestReady = false;
            }
            case VoiceMessage.MANIFEST -> {
                if (!receive || cache == null || manifestReady || manifest.size() >= VoiceLibrary.MAX_CLIPS) throw new IllegalArgumentException("Unexpected manifest");
                var entry = new VoiceCache.Entry(message.owner(), message.clip(), message.total(), message.hash());
                if (manifest.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("Duplicate clip in manifest");
                long samples = manifest.values().stream().filter(e -> e.owner().equals(entry.owner())).mapToLong(e -> (e.size() - 44) / 2).sum();
                if (samples > VoiceAudio.RATE * 60) throw new IllegalArgumentException("Owner cache exceeds 60 seconds");
            }
            case VoiceMessage.MANIFEST_END -> {
                if (cache == null || manifestReady) throw new IllegalArgumentException("Unexpected manifest end");
                cache.reconcile(manifest.values()); // Revoke offline deletions BEFORE any clip is eligible for reuse.
                manifestReady = true;
                if (receive) for (var entry : manifest.values()) if (!cache.contains(entry)) missing.add(entry);
                next(token);
            }
            case VoiceMessage.CHUNK -> {
                validateActive(message);
                transfer.append(message.offset(), message.data());
            }
            case VoiceMessage.COMPLETE -> {
                validateActive(message);
                if (message.offset() != active.size()) throw new IllegalArgumentException("Invalid completion length");
                byte[] bytes = transfer.finish();
                if (token != connection.get() || !consent.acceptServerState(receive) || !manifestReady) return;
                cache.put(active, bytes); active = null; transfer = null; next(token);
            }
            case VoiceMessage.TOMBSTONE -> {
                if (cache == null) return;
                var known = manifest.get(message.clip());
                if (known != null && !known.owner().equals(message.owner())) throw new IllegalArgumentException("Tombstone owner mismatch");
                cache.delete(message.owner(), message.clip()); manifest.remove(message.clip());
                missing.removeIf(e -> e.id().equals(message.clip()));
                if (active != null && active.id().equals(message.clip())) { active = null; transfer = null; }
            }
            default -> throw new IllegalArgumentException("Unexpected server voice message");
        }
    }
    private static void validateActive(VoiceMessage message) {
        if (!consent.acceptServerState(receive) || !manifestReady || active == null || transfer == null || !active.id().equals(message.clip())
                || !active.owner().equals(message.owner()) || active.size() != message.total() || !active.hash().equals(message.hash()))
            throw new IllegalArgumentException("Unrequested or stale voice transfer");
    }
    private static void next(long token) {
        if (!consent.acceptServerState(receive) || !manifestReady || active != null || missing.isEmpty() || token != connection.get()) return;
        active = missing.removeFirst(); transfer = new VoiceAudio.Transfer(active.size(), active.hash());
        var request = new VoiceMessage(VoiceMessage.REQUEST, active.owner(), active.id(), generation, 0, 0, "", new byte[0]);
        MinecraftClient.getInstance().execute(() -> {
            if (token == connection.get() && ClientPlayNetworking.canSend(VoiceMessage.ID)) ClientPlayNetworking.send(request);
        });
    }
}


