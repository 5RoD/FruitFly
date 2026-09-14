package dev.flybrain.voice;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

public final class VoiceCheck {
    public static void main(String[] args) throws Exception {
        VoiceProtocolCheck.run();
        VoiceServerProtocolCheck.run();
        VoiceClientProtocolCheck.run();
        VoiceMalformedCheck.run();
        var root = Files.createTempDirectory("flybrain-voice-check-");
        var owner = UUID.randomUUID();
        var library = new VoiceLibrary(root);
        check(!library.capturing(owner, 1), "capture defaults off");
        long generation = library.start(owner, 1);
        check(library.capturing(owner, 1), "self consent enables capture");
        check(!library.validCapture(owner, generation, 1_800_002), "30 minute expiry");
        short[] pcm = new short[48_000];
        Arrays.fill(pcm, (short)1200);
        var blocked = java.util.concurrent.CompletableFuture.runAsync(() -> {
            synchronized (library) {
                var reads = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    library.captureGeneration(owner, 1); library.revision(); library.all(); library.full(owner);
                });
                try { reads.get(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError("metadata must not wait for disk mutation monitor", e); }
            }
        });
        blocked.get(2, java.util.concurrent.TimeUnit.SECONDS);
        var wav = VoiceAudio.wav(pcm);
        check(Arrays.equals(pcm, VoiceAudio.pcm(wav)), "PCM WAV round trip");
        expect(() -> VoiceAudio.pcm(new byte[44]), "reject malformed WAV");
        expect(() -> VoiceAudio.wav(new short[47_999]), "reject subsecond clip");
        expect(() -> VoiceAudio.wav(new short[288_001]), "reject long clip");
        var clip = library.save(owner, generation, pcm, 2);
        check(clip != null && library.list(owner).size() == 1, "capture publishes canonical clip");
        library.stop(owner);
        check(!library.validCapture(owner, generation, 3), "stop invalidates queued generation");
        check(library.save(owner, generation, pcm, 3) == null, "revoked publication blocked");
        check(library.reusable(clip), "saved replay grant survives stop");
        check(!library.shared(clip), "sharing defaults off");
        library.share(owner, true);
        check(library.shared(clip), "explicit sharing enables distribution");
        library.share(owner, false);
        check(!library.shared(clip), "sharing revoke blocks distribution");
        var reloaded = new VoiceLibrary(root);
        check(!reloaded.capturing(owner, 4), "capture remains off after restart");
        check(reloaded.list(owner).size() == 1, "saved clip persists");
        generation = reloaded.start(owner, 4);
        for (int i = 1; i < 60; i++) check(reloaded.save(owner, generation, pcm, 5) != null, "budget accepts 60 seconds");
        check(reloaded.save(owner, generation, pcm, 5) == null, "budget rejects overflow without deleting");
        check(reloaded.list(owner).size() == 60, "old clips retained at cap");
        check(!reloaded.delete(UUID.randomUUID(), clip.id()), "other user cannot delete clip");
        check(reloaded.delete(owner, clip.id()), "owner deletes clip");
        check(!reloaded.reusable(clip), "deleted clip loses replay grant");
        var bytes = wav;
        var transfer = new VoiceAudio.Transfer(bytes.length, VoiceAudio.hash(bytes));
        for (int offset = 0; offset < bytes.length; offset += VoiceAudio.CHUNK_BYTES)
            transfer.append(offset, Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + VoiceAudio.CHUNK_BYTES)));
        check(Arrays.equals(bytes, transfer.finish()), "bounded transfer round trip");
        expect(() -> new VoiceAudio.Transfer(VoiceAudio.MAX_WAV_BYTES + 1, VoiceAudio.hash(bytes)), "oversized manifest rejected");
        expect(() -> new VoiceAudio.Transfer(bytes.length, "../escape"), "invalid hash rejected");
        expect(() -> new VoiceAudio.Transfer(bytes.length, VoiceAudio.hash(bytes)).append(1, new byte[1]), "offset mismatch rejected");
        expect(() -> new VoiceAudio.Transfer(bytes.length, VoiceAudio.hash(bytes)).finish(), "truncated transfer rejected");
        var corrupt = new VoiceAudio.Transfer(bytes.length, VoiceAudio.hash(bytes));
        byte[] altered = bytes.clone(); altered[44] ^= 1;
        for (int offset = 0; offset < altered.length; offset += VoiceAudio.CHUNK_BYTES)
            corrupt.append(offset, Arrays.copyOfRange(altered, offset, Math.min(altered.length, offset + VoiceAudio.CHUNK_BYTES)));
        expect(corrupt::finish, "hash mismatch rejected");
        check(VoiceAudio.distort(pcm).length >= pcm.length, "distortion preserves recognizable duration");
        reloaded.revokeAll(owner);
        check(reloaded.all().isEmpty(), "queued clear immediately revokes replay metadata");
        reloaded.clear(owner);
        check(reloaded.list(owner).isEmpty(), "clear removes canonical clips");
        System.out.println("VoiceCheck PASS: consent generations, expiry, ownership, persistence, 60-second cap, WAV, transfer bounds/hash, distortion, deletion");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void expect(Checked body, String message) throws Exception {
        try { body.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}






