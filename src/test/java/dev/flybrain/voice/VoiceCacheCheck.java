package dev.flybrain.voice;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

public final class VoiceCacheCheck {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("flybrain-voice-cache-check-");
        var cache = new VoiceCache(root);
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        byte[] wav = VoiceAudio.wav(new short[48_000]);
        var entry = new VoiceCache.Entry(owner, id, wav.length, VoiceAudio.hash(wav));
        cache.put(entry, wav);
        if (!cache.contains(entry)) throw new AssertionError("persisted cache clip missing");
        cache.reconcile(List.of(entry));
        if (!cache.contains(entry)) throw new AssertionError("authorized clip was removed");
        cache.reconcile(List.of());
        if (cache.contains(entry)) throw new AssertionError("offline deletion not reconciled");
        try { cache.put(entry, new byte[wav.length]); throw new AssertionError("invalid bytes cached"); }
        catch (IllegalArgumentException expected) {}
        cache.put(entry, wav); cache.delete(owner, id);
        if (cache.contains(entry)) throw new AssertionError("tombstone not applied");
        var filler = root.resolve("quota-fill.bin");
        try (var file = new java.io.RandomAccessFile(filler.toFile(), "rw")) { file.setLength(VoiceCache.WORLD_BUDGET); }
        try { cache.put(entry, wav); throw new AssertionError("cache quota exceeded"); }
        catch (java.io.IOException expected) {}
        Files.delete(filler);
        System.out.println("VoiceCacheCheck PASS: atomic verified cache, manifest retention, offline deletion reconciliation, corrupt payload rejection, tombstone");
    }
}

