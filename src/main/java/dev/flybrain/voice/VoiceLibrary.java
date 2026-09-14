package dev.flybrain.voice;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable metadata snapshots keep microphone/game threads independent of disk IO. */
public final class VoiceLibrary {
    public static final int MAX_CLIPS = 4096;
    public record Clip(UUID owner, UUID id, int samples, String hash) {}
    private record Capture(long generation, long until) {}
    private record State(Map<UUID, Clip> clips, Map<UUID, Boolean> sharing, long revision) {}
    private final Path root;
    private volatile State state = new State(Map.of(), Map.of(), 0);
    private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong(), revocations = new AtomicLong();
    private final java.util.Set<UUID> revoked = ConcurrentHashMap.newKeySet();
    public final UUID serverId;

    public VoiceLibrary(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        Path identity = this.root.resolve("server-id.txt");
        if (!Files.exists(identity)) atomic(identity, UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
        serverId = UUID.fromString(Files.readString(identity).trim());
        Path index = this.root.resolve("library.properties");
        if (!Files.exists(index)) return;
        if (Files.size(index) > 1_048_576) throw new IOException("Voice library index exceeds limit");
        var props = new Properties(); props.load(new StringReader(Files.readString(index)));
        var clips = new LinkedHashMap<UUID, Clip>(); var sharing = new LinkedHashMap<UUID, Boolean>();
        try {
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("share.")) { sharing.put(UUID.fromString(key.substring(6)), Boolean.parseBoolean(props.getProperty(key))); continue; }
                if (!key.startsWith("clip.")) continue;
                UUID id = UUID.fromString(key.substring(5)); String[] values = props.getProperty(key).split(",");
                if (values.length != 3 || clips.size() >= MAX_CLIPS) throw new IllegalArgumentException("Invalid voice index");
                var clip = new Clip(UUID.fromString(values[0]), id, Integer.parseInt(values[1]), values[2]);
                byte[] bytes = readBounded(path(clip));
                if (!VoiceAudio.hash(bytes).equals(clip.hash()) || VoiceAudio.pcm(bytes).length != clip.samples())
                    throw new IllegalArgumentException("Voice clip verification failed: " + id);
                if (clips.values().stream().filter(c -> c.owner().equals(clip.owner())).mapToInt(Clip::samples).sum() + clip.samples() > VoiceAudio.RATE * 60)
                    throw new IllegalArgumentException("Owner library exceeds 60 seconds");
                clips.put(id, clip);
            }
        } catch (IllegalArgumentException error) { throw new IOException("Voice library index is invalid", error); }
        state = new State(Map.copyOf(clips), Map.copyOf(sharing), 0);
    }
    public long start(UUID owner, long now) {
        var capture = new Capture(generation.incrementAndGet(), now + 30 * 60 * 1000L); captures.put(owner, capture); return capture.generation();
    }
    public void stop(UUID owner) { captures.remove(owner); }
    public void stopAll() { captures.clear(); }
    public long captureGeneration(UUID owner, long now) { var c = captures.get(owner); return c != null && now < c.until() ? c.generation() : -1; }
    public boolean capturing(UUID owner, long now) { return captureGeneration(owner, now) >= 0; }
    public boolean validCapture(UUID owner, long expected, long now) { return expected >= 0 && captureGeneration(owner, now) == expected; }
    public boolean full(UUID owner) { return samples(owner) >= VoiceAudio.RATE * 60 || state.clips().size() >= MAX_CLIPS; }
    // Only disk mutations serialize. No monitor held here is needed by packet/game-thread metadata reads or revocation.
    public synchronized Clip save(UUID owner, long expected, short[] pcm, long now) throws IOException {
        long began = System.nanoTime();
        if (!validCapture(owner, expected, now)) return null;
        if (pcm.length < VoiceAudio.MIN_SAMPLES || pcm.length > VoiceAudio.MAX_SAMPLES) throw new IllegalArgumentException("Clip duration");
        if (samples(owner) + pcm.length > VoiceAudio.RATE * 60 || state.clips().size() >= MAX_CLIPS) return null;
        byte[] wav = VoiceAudio.wav(pcm); var clip = new Clip(owner, UUID.randomUUID(), pcm.length, VoiceAudio.hash(wav));
        Files.createDirectories(path(clip).getParent()); atomic(path(clip), wav);
        State previous = state; var clips = new LinkedHashMap<>(previous.clips()); clips.put(clip.id(), clip);
        var next = new State(Map.copyOf(clips), previous.sharing(), previous.revision() + 1);
        if (!validCapture(owner, expected, now + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began))) { Files.deleteIfExists(path(clip)); return null; }
        try { persist(next); } catch (IOException e) { Files.deleteIfExists(path(clip)); throw e; }
        boolean[] published = {false};
        captures.computeIfPresent(owner, (id, capture) -> {
            if (capture.generation() == expected && now + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < capture.until()) { state = next; published[0] = true; }
            return capture;
        });
        if (!published[0]) { persist(previous); Files.deleteIfExists(path(clip)); return null; }
        return clip;
    }
    public boolean reusable(Clip clip) { return clip != null && !revoked.contains(clip.id()) && clip.equals(state.clips().get(clip.id())); }
    public void revoke(UUID owner, UUID id) { var clip = state.clips().get(id); if (clip != null && clip.owner().equals(owner) && revoked.add(id)) revocations.incrementAndGet(); }
    public void revokeAll(UUID owner) { for (var clip : state.clips().values()) if (clip.owner().equals(owner)) revoke(owner, clip.id()); }
    public boolean shared(Clip clip) { var s = state; return clip != null && !revoked.contains(clip.id()) && clip.equals(s.clips().get(clip.id())) && s.sharing().getOrDefault(clip.owner(), false); }
    public boolean sharing(UUID owner) { return state.sharing().getOrDefault(owner, false); }
    public synchronized void share(UUID owner, boolean enabled) throws IOException {
        var current = state; var sharing = new LinkedHashMap<>(current.sharing());
        if (enabled) sharing.put(owner, true); else sharing.remove(owner);
        var next = new State(current.clips(), Map.copyOf(sharing), current.revision() + 1);
        if (!enabled) state = next; // Revocation takes effect before disk completion and remains fail-closed on IO error.
        persist(next); state = next;
    }
    public List<Clip> list(UUID owner) { return state.clips().values().stream().filter(c -> c.owner().equals(owner) && reusable(c)).toList(); }
    public List<Clip> all() { return state.clips().values().stream().filter(this::reusable).toList(); }
    public Clip get(UUID id) { return revoked.contains(id) ? null : state.clips().get(id); }
    public long revision() { return state.revision() + revocations.get(); }
    public int samples(UUID owner) { return state.clips().values().stream().filter(c -> c.owner().equals(owner)).mapToInt(Clip::samples).sum(); }
    public byte[] read(Clip clip) throws IOException {
        if (!reusable(clip)) throw new IOException("Clip was deleted");
        var data = readBounded(path(clip));
        if (!VoiceAudio.hash(data).equals(clip.hash()) || !reusable(clip)) throw new IOException("Clip deleted or checksum mismatch"); return data;
    }
    public synchronized boolean delete(UUID owner, UUID id) throws IOException {
        var current = state; var clip = current.clips().get(id); if (clip == null || !clip.owner().equals(owner)) return false;
        stop(owner); var clips = new LinkedHashMap<>(current.clips()); clips.remove(id);
        state = new State(Map.copyOf(clips), current.sharing(), current.revision() + 1);
        persist(state); Files.deleteIfExists(path(clip)); revoked.remove(id); return true;
    }
    public synchronized void clear(UUID owner) throws IOException {
        stop(owner); for (var clip : new ArrayList<>(state.clips().values())) if (clip.owner().equals(owner)) delete(owner, clip.id());
    }
    private Path path(Clip clip) { return root.resolve(clip.owner().toString()).resolve(clip.id() + ".wav"); }
    private void persist(State value) throws IOException {
        var props = new Properties();
        value.sharing().forEach((id, on) -> { if (on) props.setProperty("share." + id, "true"); });
        value.clips().values().forEach(c -> props.setProperty("clip." + c.id(), c.owner() + "," + c.samples() + "," + c.hash()));
        var writer = new StringWriter(); props.store(writer, "FlyBrain saved voice replay grants; capture is never persisted");
        atomic(root.resolve("library.properties"), writer.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] readBounded(Path path) throws IOException {
        if (Files.size(path) > VoiceAudio.MAX_WAV_BYTES) throw new IOException("Voice file exceeds clip limit");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(VoiceAudio.MAX_WAV_BYTES + 1);
            if (bytes.length > VoiceAudio.MAX_WAV_BYTES) throw new IOException("Voice file exceeds clip limit"); return bytes;
        }
    }
    public static void atomic(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), ".voice-", ".tmp");
        try { Files.write(temp, bytes); Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
}


