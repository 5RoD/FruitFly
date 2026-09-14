package dev.flybrain.voice;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import java.util.UUID;

public final class VoiceProtocolCheck {
    public static void run() throws Exception {
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        byte[] source = {1, 2, 3};
        var message = new VoiceMessage(VoiceMessage.CHUNK, owner, id, 7, 11, 123, "", source);
        source[0] = 99;
        if (message.data()[0] != 1) throw new AssertionError("constructor must detach caller bytes");
        byte[] copy = message.data(); copy[0] = 88;
        if (message.data()[0] != 1) throw new AssertionError("accessor must detach mutable bytes");
        var buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            VoiceMessage.CODEC.encode(buffer, message);
            var decoded = VoiceMessage.CODEC.decode(buffer);
            if (!decoded.owner().equals(owner) || !decoded.clip().equals(id) || decoded.generation() != 7
                || decoded.offset() != 11 || decoded.total() != 123 || decoded.data()[2] != 3 || !decoded.getId().equals(VoiceMessage.ID))
                throw new AssertionError("wire codec round trip");
        } finally { buffer.release(); }
        for (int kind : new int[]{-1, 9}) expect(() -> new VoiceMessage(kind, owner, id, 0, 0, 0, "", new byte[0]));
        expect(() -> new VoiceMessage(0, null, id, 0, 0, 0, "", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, null, 0, 0, 0, "", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, -1, 0, 0, "", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, 0, -1, 0, "", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, 0, 0, -1, "", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, 0, 0, VoiceAudio.MAX_WAV_BYTES + 1, "", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, 0, 0, 0, null, new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, 0, 0, 0, "bad", new byte[0]));
        expect(() -> new VoiceMessage(0, owner, id, 0, 0, 0, "", null));
        expect(() -> new VoiceMessage(0, owner, id, 0, 0, 0, "", new byte[VoiceAudio.CHUNK_BYTES + 1]));
        new VoiceMessage(0, owner, id, 0, 0, 0, VoiceAudio.hash(new byte[0]), new byte[0]);
        System.out.println("VoiceProtocolCheck PASS: codec round trip, defensive copies, all envelope bounds and identity checks");
    }
    private static void expect(Runnable check) { try { check.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Malformed envelope accepted"); }
}
