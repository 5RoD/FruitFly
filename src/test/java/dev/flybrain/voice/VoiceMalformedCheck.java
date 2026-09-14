package dev.flybrain.voice;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class VoiceMalformedCheck {
    public static void run() throws Exception {
        byte[] valid = VoiceAudio.wav(new short[48_000]);
        for (int offset : new int[]{0,4,8,12,16,20,22,24,28,32,34,36,40}) {
            byte[] malformed = valid.clone(); malformed[offset] ^= 1;
            bad(() -> VoiceAudio.pcm(malformed));
        }
        bad(() -> VoiceAudio.pcm(Arrays.copyOf(valid, valid.length + 1)));
        bad(() -> VoiceAudio.pcm(new byte[VoiceAudio.MAX_WAV_BYTES + 2]));
        bad(() -> new VoiceAudio.Transfer(valid.length - 2, VoiceAudio.hash(valid)));
        bad(() -> new VoiceAudio.Transfer(valid.length, "bad"));
        bad(() -> new VoiceAudio.Transfer(valid.length, VoiceAudio.hash(valid)).append(0, new byte[0]));
        bad(() -> new VoiceAudio.Transfer(valid.length, VoiceAudio.hash(valid)).append(0, new byte[VoiceAudio.CHUNK_BYTES + 1]));
        var transfer = new VoiceAudio.Transfer(valid.length, VoiceAudio.hash(valid));
        for (int offset = 0; offset < valid.length; offset += VoiceAudio.CHUNK_BYTES)
            transfer.append(offset, Arrays.copyOfRange(valid, offset, Math.min(valid.length, offset + VoiceAudio.CHUNK_BYTES)));
        bad(() -> transfer.append(valid.length, new byte[1]));
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        bad(() -> new VoiceCache.Entry(null, id, valid.length, VoiceAudio.hash(valid)));
        bad(() -> new VoiceCache.Entry(owner, null, valid.length, VoiceAudio.hash(valid)));
        bad(() -> new VoiceCache.Entry(owner, id, valid.length - 1, VoiceAudio.hash(valid)));
        bad(() -> new VoiceCache.Entry(owner, id, VoiceAudio.MAX_WAV_BYTES + 1, VoiceAudio.hash(valid)));
        bad(() -> new VoiceCache.Entry(owner, id, valid.length, "bad"));
        var path = Files.createTempDirectory("voice-malformed-cache-"); var cache = new VoiceCache(path);
        var entry = new VoiceCache.Entry(owner, id, valid.length, VoiceAudio.hash(valid));
        bad(() -> new VoiceCache(path, path.resolve("other")));
        bad(() -> cache.reconcile(java.util.Collections.nCopies(VoiceLibrary.MAX_CLIPS + 1, entry)));
        Files.createDirectory(path.resolve("not-an-owner")); Files.writeString(path.resolve("not-an-owner/file.wav"), "leave me");
        Files.createDirectory(path.resolve(owner.toString()));
        Files.writeString(path.resolve(owner.toString()).resolve("notes.txt"), "leave me");
        Files.writeString(path.resolve(owner.toString()).resolve("not-a-uuid.wav"), "leave me");
        cache.reconcile(List.of());
        check(Files.exists(path.resolve(owner.toString()).resolve("not-a-uuid.wav")), "unmanaged filename left alone");
        cache.put(entry, valid);
        var file = path.resolve(owner.toString()).resolve(id + ".wav");
        Files.write(file, new byte[5]); check(!cache.contains(entry), "cached size mismatch rejected");
        byte[] corrupt = valid.clone(); corrupt[44] ^= 1; Files.write(file, corrupt);
        check(!cache.contains(entry), "cached checksum mismatch rejected");
        var root = Files.createTempDirectory("voice-malformed-library-"); var library = new VoiceLibrary(root);
        library.share(owner, true); check(new VoiceLibrary(root).sharing(owner), "persistent share grant restored");
        check(!library.reusable(null) && !library.shared(null), "absent clip has no grants");
        long generation = library.start(owner, 1);
        bad(() -> library.save(owner, generation, new short[1], 2));
        bad(() -> library.save(owner, generation, new short[VoiceAudio.MAX_SAMPLES + 1], 2));
        var clip = library.save(owner, generation, new short[48_000], 2);
        library.revoke(UUID.randomUUID(), clip.id()); check(library.reusable(clip), "other owner cannot revoke");
        check(Arrays.equals(valid, library.read(clip)), "bounded canonical read");
        library.revoke(owner, clip.id()); io(() -> library.read(clip));
        Files.writeString(root.resolve("library.properties"), "clip." + UUID.randomUUID() + "=invalid\n"); io(() -> new VoiceLibrary(root));
        Files.writeString(root.resolve("library.properties"), "share.not-a-uuid=true\n"); io(() -> new VoiceLibrary(root));
        Files.writeString(root.resolve("library.properties"), "unknown-key=ignored\n"); check(new VoiceLibrary(root).all().isEmpty(), "unknown index metadata ignored");
        try (var oversized = new java.io.RandomAccessFile(root.resolve("library.properties").toFile(), "rw")) { oversized.setLength(1_048_577); }
        io(() -> new VoiceLibrary(root));
        var corruptRoot = Files.createTempDirectory("voice-corrupt-index-"); var corruptLibrary = new VoiceLibrary(corruptRoot);
        long corruptGeneration = corruptLibrary.start(owner, 1); var corruptClip = corruptLibrary.save(owner, corruptGeneration, new short[48_000], 2);
        Files.write(corruptRoot.resolve(owner.toString()).resolve(corruptClip.id() + ".wav"), corrupt);
        io(() -> new VoiceLibrary(corruptRoot));
        var excessiveRoot = Files.createTempDirectory("voice-excessive-index-"); new VoiceLibrary(excessiveRoot);
        Files.createDirectories(excessiveRoot.resolve(owner.toString())); var index = new StringBuilder();
        for (int i = 0; i < 61; i++) {
            UUID clipId = UUID.randomUUID(); Files.write(excessiveRoot.resolve(owner.toString()).resolve(clipId + ".wav"), valid);
            index.append("clip.").append(clipId).append('=').append(owner).append(",48000,").append(VoiceAudio.hash(valid)).append('\n');
        }
        Files.writeString(excessiveRoot.resolve("library.properties"), index); io(() -> new VoiceLibrary(excessiveRoot));
        var failureRoot = Files.createTempDirectory("voice-write-failure-"); var failure = new VoiceLibrary(failureRoot);
        Files.createDirectory(failureRoot.resolve("library.properties")); long consent = failure.start(owner, 1);
        io(() -> failure.save(owner, consent, new short[48_000], 2));
        check(failure.all().isEmpty(), "failed index write does not publish clip");
        var oversizedClip = failureRoot.resolve("oversized.wav");
        try (var fileOut = new java.io.RandomAccessFile(oversizedClip.toFile(), "rw")) { fileOut.setLength(VoiceAudio.MAX_WAV_BYTES + 1); }
        io(() -> VoiceLibrary.readBounded(oversizedClip));
        System.out.println("VoiceMalformedCheck PASS: all canonical WAV fields, transfer boundaries, cache metadata/path/quota, corrupt indexes/files and failed publication");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void bad(Checked body) throws Exception { try { body.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Malformed input accepted"); }
    private static void io(Checked body) throws Exception { try { body.run(); } catch (java.io.IOException expected) { return; } throw new AssertionError("Invalid storage accepted"); }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}

