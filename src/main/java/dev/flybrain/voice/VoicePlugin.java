package dev.flybrain.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.flybrain.FlyBrain;
import dev.flybrain.entity.CreatureEntity;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/** Loaded only through SVC's optional voicechat entrypoint. */
public final class VoicePlugin implements VoicechatPlugin {
    private volatile VoicechatServerApi api;
    private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();
    private final Map<UUID, Playback> playbacks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { var t = new Thread(r, "flybrain-voice-decode"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private record Frame(long generation, byte[] opus) {}
    private static final class Playback {
        final ArrayDeque<UUID> recent = new ArrayDeque<>();
        long next;
        volatile AudioPlayer player;
        volatile VoiceLibrary.Clip clip;
        volatile boolean eligible;
        EntityAudioChannel channel;
    }
    @Override public String getPluginId() { return "flybrain"; }
    @Override public void registerEvents(EventRegistration events) {
        workers.allowCoreThreadTimeOut(true);
        events.registerEvent(VoicechatServerStartedEvent.class, event -> {
            api = event.getVoicechat();
            VoiceIntegration.available = () -> api != null;
            VoiceIntegration.recordingAllowed = this::recordingAllowed;
            VoiceIntegration.invalidate = this::invalidate;
            VoiceIntegration.voiceTick = this::tick;
        });
        events.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            api = null; for (UUID id : new ArrayList<>(streams.keySet())) invalidate(id);
            for (var playback : playbacks.values()) stop(playback); playbacks.clear();
            var library = VoiceIntegration.library; if (library != null) library.stopAll();
        });
        events.registerEvent(PlayerConnectedEvent.class, event -> VoiceIntegration.resetConnection(event.getConnection().getPlayer().getUuid()));
        events.registerEvent(PlayerDisconnectedEvent.class, event -> VoiceIntegration.resetConnection(event.getPlayerUuid()));
        events.registerEvent(PlayerStateChangedEvent.class, event -> {
            if (event.isDisabled() || event.isDisconnected()) VoiceIntegration.stopCapture(event.getPlayerUuid());
        });
        events.registerEvent(JoinGroupEvent.class, event -> {
            UUID owner = event.getConnection().getPlayer().getUuid(); VoiceIntegration.stopCapture(owner);
        });
        events.registerEvent(MicrophoneMuteEvent.class, event -> VoiceIntegration.clientMute.accept(event.isDisabled()));
        events.registerEvent(ClientVoicechatConnectionEvent.class, event -> { if (!event.isConnected()) VoiceIntegration.clientMute.accept(true); });
        events.registerEvent(MicrophonePacketEvent.class, this::microphone);
    }
    private boolean recordingAllowed() { var a = api; return a != null && a.getServerConfig().getBoolean("allow_recording", false); }
    private boolean valid(UUID owner, long generation, VoiceLibrary library) {
        var a = api;
        if (a == null || library != VoiceIntegration.library || !recordingAllowed()
                || !library.validCapture(owner, generation, System.currentTimeMillis())) return false;
        // SVC connection snapshots become stale, and this event runs BEFORE group routing.
        var current = a.getConnectionOf(owner);
        return current != null && current.isConnected() && current.isInstalled() && !current.isDisabled() && !current.isInGroup() && current.getGroup() == null;
    }
    private void microphone(MicrophonePacketEvent event) {
        if (event.isCancelled() || event.getSenderConnection() == null) return;
        UUID owner = event.getSenderConnection().getPlayer().getUuid(); var library = VoiceIntegration.library;
        if (library == null) return;
        long generation = library.captureGeneration(owner, System.currentTimeMillis());
        if (!valid(owner, generation, library)) { discardStream(owner); return; }
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (opus == null || opus.length > 4096) { discardStream(owner); return; }
        Stream stream;
        synchronized (streams) {
            stream = streams.get(owner);
            if (stream == null) {
                if (opus.length == 0) return;
                if (streams.size() >= 32) { VoiceIntegration.stopCapture(owner); VoiceIntegration.notify(owner, "Voice capture worker limit reached; capture stopped. Try start again later."); return; }
                stream = new Stream(owner, generation, library); streams.put(owner, stream);
            }
        }
        stream.offer(new Frame(generation, opus.clone()));
    }
    private void discardStream(UUID owner) { var stream = streams.remove(owner); if (stream != null) stream.discard(); }
    private void invalidate(UUID owner) {
        discardStream(owner);
        for (var playback : playbacks.values()) if (playback.clip != null && playback.clip.owner().equals(owner)) stop(playback);
    }
    private final class Stream implements Runnable {
        final UUID owner;
        final long generation;
        final VoiceLibrary library;
        final ArrayDeque<Frame> queue = new ArrayDeque<>();
        final short[] pcm = new short[VoiceAudio.MAX_SAMPLES];
        volatile long lastPacket = System.currentTimeMillis();
        volatile boolean discarded;
        boolean scheduled;
        int length;
        OpusDecoder decoder;
        Stream(UUID owner, long generation, VoiceLibrary library) { this.owner = owner; this.generation = generation; this.library = library; }
        synchronized void offer(Frame frame) {
            if (discarded) return;
            lastPacket = System.currentTimeMillis();
            if (queue.size() >= 96) {
                discard(); VoiceIntegration.stopCapture(owner); VoiceIntegration.notify(owner, "Voice capture queue full; partial audio dropped and capture stopped."); return;
            }
            queue.add(frame);
            if (!scheduled) { scheduled = true; schedule(); }
        }
        private void schedule() {
            try { workers.execute(this); }
            catch (java.util.concurrent.RejectedExecutionException e) {
                discarded = true; scheduled = false; queue.clear(); streams.remove(owner, this);
                if (decoder != null) { decoder.close(); decoder = null; }
                VoiceIntegration.stopCapture(owner); VoiceIntegration.notify(owner, "Voice capture queue busy; capture stopped.");
            }
        }
        synchronized void discard() {
            discarded = true; queue.clear();
            if (!scheduled) { scheduled = true; schedule(); }
        }
        @Override public void run() {
            try {
                for (int count = 0; count < 16; count++) {
                    Frame frame;
                    synchronized (this) {
                        if (discarded) { close(); return; }
                        frame = queue.poll();
                        if (frame == null) { scheduled = false; return; }
                    }
                    if (frame.generation() != generation || !valid(owner, generation, library)) { discard(); continue; }
                    if (frame.opus().length == 0) { publish(); if (decoder != null) decoder.resetState(); continue; }
                    if (decoder == null) { var a = api; if (a == null) { discard(); continue; } decoder = a.createDecoder(); }
                    short[] decoded = decoder.decode(frame.opus());
                    if (discarded || !valid(owner, generation, library)) { discard(); continue; }
                    if (decoded == null || decoded.length > VoiceAudio.RATE) throw new IllegalArgumentException("Unexpected decoded frame length");
                    for (int offset = 0; offset < decoded.length;) {
                        int size = Math.min(decoded.length - offset, pcm.length - length);
                        System.arraycopy(decoded, offset, pcm, length, size); length += size; offset += size;
                        if (length == pcm.length) publish();
                    }
                }
                synchronized (this) { if (discarded) close(); else if (queue.isEmpty()) scheduled = false; else schedule(); }
            } catch (Exception e) {
                VoiceIntegration.LOG.error("Voice capture failed for {}", owner, e);
                synchronized (this) { discarded = true; close(); }
                VoiceIntegration.stopCapture(owner); VoiceIntegration.notify(owner, "Voice capture failed and stopped; check server log.");
            }
        }
        private void publish() throws java.io.IOException {
            if (length < VoiceAudio.MIN_SAMPLES || discarded || !valid(owner, generation, library)) { Arrays.fill(pcm, (short)0); length = 0; return; }
            short[] clip = Arrays.copyOf(pcm, length); Arrays.fill(pcm, (short)0); length = 0;
            if (discarded || !valid(owner, generation, library)) return;
            var saved = library.save(owner, generation, clip, System.currentTimeMillis()); Arrays.fill(clip, (short)0);
            if (saved == null || library.full(owner)) {
                VoiceIntegration.stopCapture(owner);
                VoiceIntegration.notify(owner, "Voice library cap reached (60 seconds per owner or server clip cap). Capture stopped; old clips were kept. Delete your clips or clear to make space.");
            } else VoiceIntegration.notify(owner, "Saved your voice clip " + saved.id() + " (" + saved.samples() / 48_000.0 + "s).");
        }
        private void close() {
            queue.clear(); length = 0; Arrays.fill(pcm, (short)0);
            if (decoder != null) { decoder.close(); decoder = null; }
            scheduled = false; streams.remove(owner, this);
        }
    }
    private void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (var stream : streams.values()) {
            if (!valid(stream.owner, stream.generation, stream.library)) { discardStream(stream.owner); continue; }
            if (now - stream.lastPacket > 400) stream.offer(new Frame(stream.generation, new byte[0]));
        }
        var present = new java.util.HashSet<UUID>();
        for (var world : server.getWorlds()) for (CreatureEntity creature : world.getEntitiesByType(FlyBrain.CREATURE, entity -> true)) {
            UUID id = creature.getUuid(); present.add(id);
            var playback = playbacks.computeIfAbsent(id, unused -> { var p = new Playback(); p.next = now + cooldown(); return p; });
            playback.eligible = canMimic(creature.isAlive(), creature.animationPaused(), creature.behaviorName());
            if (!playback.eligible || api == null || VoiceIntegration.library == null || !recordingAllowed()) { stop(playback); continue; }
            if (playback.player != null && !playback.player.isStopped()) {
                var library = VoiceIntegration.library;
                if (playback.clip == null || !library.reusable(playback.clip)) stop(playback);
                continue;
            }
            if (now < playback.next) continue;
            playback.next = now + cooldown(); play(creature, playback);
        }
        for (var entry : new ArrayList<>(playbacks.entrySet())) if (!present.contains(entry.getKey())) { stop(entry.getValue()); playbacks.remove(entry.getKey()); }
    }
    static boolean canMimic(boolean alive, boolean paused, String behavior) { return alive && !paused && "curious stalk".equals(behavior); }
    private static long cooldown() { return ThreadLocalRandom.current().nextLong(45_000, 90_001); }
    private void play(CreatureEntity creature, Playback playback) {
        var a = api; var library = VoiceIntegration.library; if (a == null || library == null) return;
        var candidates = library.all().stream().filter(c -> !playback.recent.contains(c.id()))
                .filter(c -> creature.getWorld().getPlayers().stream().anyMatch(p -> VoiceIntegration.receiving(p.getUuid())
                        && p.squaredDistanceTo(creature) < 24 * 24 && (p.getUuid().equals(c.owner()) || VoiceIntegration.shared(c)))) .toList();
        if (candidates.isEmpty()) return; // Fewer than four clips intentionally fall silent rather than repeat the last three.
        var clip = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        playback.clip = clip;
        try {
            // Audio file work stays off server/network threads; the server schedules the actual entity channel creation.
            workers.execute(() -> {
                try {
                    short[] pcm = VoiceAudio.distort(VoiceAudio.pcm(library.read(clip)));
                    var server = VoiceIntegration.server; if (server == null) return;
                    server.execute(() -> {
                        if (api != a || !playback.eligible || playback.clip != clip || !library.reusable(clip) || !creature.isAlive()
                                || !canMimic(creature.isAlive(), creature.animationPaused(), creature.behaviorName())) return;
                        if (playback.channel == null || playback.channel.isClosed()) playback.channel = a.createEntityAudioChannel(UUID.randomUUID(), a.fromEntity(creature));
                        if (playback.channel == null) { VoiceIntegration.LOG.error("SVC rejected entity voice channel for {}", creature.getUuid()); return; }
                        playback.channel.setDistance(24);
                        playback.channel.setFilter(player -> {
                            var current = a.getConnectionOf(player.getUuid());
                            return api == a && playback.eligible && canMimic(creature.isAlive(), creature.animationPaused(), creature.behaviorName())
                                    && playback.clip == clip && library == VoiceIntegration.library
                                    && recordingAllowed() && library.reusable(clip) && VoiceIntegration.receiving(player.getUuid())
                                    && current != null && current.isConnected() && !current.isDisabled()
                                    && (player.getUuid().equals(clip.owner()) || VoiceIntegration.shared(clip));
                        });
                        playback.player = a.createAudioPlayer(playback.channel, a.createEncoder(), pcm);
                        playback.recent.addLast(clip.id()); while (playback.recent.size() > 3) playback.recent.removeFirst();
                        playback.player.startPlaying();
                    });
                } catch (Exception e) { VoiceIntegration.LOG.error("Voice playback preparation failed", e); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { VoiceIntegration.LOG.warn("Voice playback skipped: worker queue full"); }
    }
    private static void stop(Playback playback) {
        playback.eligible = false; playback.clip = null;
        var player = playback.player; if (player != null && !player.isStopped()) player.stopPlaying();
        playback.player = null;
    }
}



