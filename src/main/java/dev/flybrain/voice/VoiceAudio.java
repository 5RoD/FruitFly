package dev.flybrain.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical, deliberately narrow PCM format; never accepts paths or arbitrary audio codecs. */
public final class VoiceAudio {
    public static final int RATE = 48_000, MIN_SAMPLES = RATE, MAX_SAMPLES = RATE * 6;
    public static final int MAX_WAV_BYTES = MAX_SAMPLES * 2 + 44, CHUNK_BYTES = 16 * 1024;
    private VoiceAudio() {}
    public static byte[] wav(short[] pcm) {
        if (pcm.length < MIN_SAMPLES || pcm.length > MAX_SAMPLES) throw new IllegalArgumentException("Clip must be 1-6 seconds");
        var b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(b.capacity() - 8);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1);
        b.putInt(RATE).putInt(RATE * 2).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
        for (short sample : pcm) b.putShort(sample);
        return b.array();
    }
    public static short[] pcm(byte[] wav) {
        if (wav.length < 44 + MIN_SAMPLES * 2 || wav.length > MAX_WAV_BYTES || wav.length % 2 != 0)
            throw new IllegalArgumentException("Invalid WAV size");
        var b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(4) != wav.length - 8 || b.getInt(8) != 0x45564157
                || b.getInt(12) != 0x20746d66 || b.getInt(16) != 16 || b.getShort(20) != 1 || b.getShort(22) != 1
                || b.getInt(24) != RATE || b.getInt(28) != RATE * 2 || b.getShort(32) != 2 || b.getShort(34) != 16
                || b.getInt(36) != 0x61746164 || b.getInt(40) != wav.length - 44) throw new IllegalArgumentException("Invalid canonical PCM WAV");
        var samples = new short[(wav.length - 44) / 2];
        b.position(44); for (int i = 0; i < samples.length; i++) samples[i] = b.getShort();
        return samples;
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static short[] distort(short[] samples) {
        // Slight pitch drop and restrained tremolo preserve recognizable words, not synthetic speech.
        short[] result = new short[(int)(samples.length / .92)];
        double filtered = 0;
        for (int i = 0; i < result.length; i++) {
            double source = i * .92;
            int a = Math.min(samples.length - 1, (int)source), b = Math.min(samples.length - 1, a + 1);
            double value = samples[a] + (samples[b] - samples[a]) * (source - a);
            filtered += .42 * (value - filtered);
            value = filtered * (.64 + .10 * Math.sin(i * 2 * Math.PI * 31 / RATE));
            int fade = Math.min(i, result.length - 1 - i);
            result[i] = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value * Math.min(1, fade / 480.0)));
        }
        return result;
    }
    public static final class Transfer {
        private final byte[] bytes;
        private final String hash;
        private int position;
        public Transfer(int size, String hash) {
            if (size < 44 + MIN_SAMPLES * 2 || size > MAX_WAV_BYTES || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid manifest");
            bytes = new byte[size]; this.hash = hash;
        }
        public void append(int offset, byte[] chunk) {
            if (offset != position || chunk.length == 0 || chunk.length > CHUNK_BYTES || chunk.length > bytes.length - position)
                throw new IllegalArgumentException("Invalid chunk offset or length");
            System.arraycopy(chunk, 0, bytes, position, chunk.length); position += chunk.length;
        }
        public byte[] finish() {
            if (position != bytes.length || !VoiceAudio.hash(bytes).equals(hash)) throw new IllegalArgumentException("Incomplete or corrupt transfer");
            pcm(bytes); return bytes.clone();
        }
    }
}
