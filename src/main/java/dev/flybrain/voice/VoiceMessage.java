package dev.flybrain.voice;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.UUID;

/** One bounded envelope; direction-specific handlers whitelist message kinds. */
public record VoiceMessage(int kind, UUID owner, UUID clip, long generation, int offset, int total, String hash, byte[] data) implements CustomPayload {
    public static final int STATE = 0, RESET = 1, MANIFEST = 2, MANIFEST_END = 3, REQUEST = 4, CHUNK = 5, COMPLETE = 6, TOMBSTONE = 7, MUTE = 8;
    public static final UUID NONE = new UUID(0, 0);
    public static final Id<VoiceMessage> ID = new Id<>(Identifier.of("flybrain", "voice_library"));
    public static final PacketCodec<RegistryByteBuf, VoiceMessage> CODEC = PacketCodec.of(VoiceMessage::write, VoiceMessage::read);
    public VoiceMessage {
        if (kind < STATE || kind > MUTE || owner == null || clip == null || generation < 0 || offset < 0 || total < 0
                || total > VoiceAudio.MAX_WAV_BYTES || hash == null || !(hash.isEmpty() || hash.matches("[0-9a-f]{64}"))
                || data == null || data.length > VoiceAudio.CHUNK_BYTES) throw new IllegalArgumentException("Invalid voice payload");
        data = data.clone();
    }
    @Override public byte[] data() { return data.clone(); }
    public static VoiceMessage control(int kind, long generation) { return new VoiceMessage(kind, NONE, NONE, generation, 0, 0, "", new byte[0]); }
    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(kind); buf.writeUuid(owner); buf.writeUuid(clip); buf.writeVarLong(generation);
        buf.writeVarInt(offset); buf.writeVarInt(total); buf.writeString(hash, 64); buf.writeByteArray(data);
    }
    private static VoiceMessage read(RegistryByteBuf buf) {
        return new VoiceMessage(buf.readVarInt(), buf.readUuid(), buf.readUuid(), buf.readVarLong(), buf.readVarInt(),
                buf.readVarInt(), buf.readString(64), buf.readByteArray(VoiceAudio.CHUNK_BYTES));
    }
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
