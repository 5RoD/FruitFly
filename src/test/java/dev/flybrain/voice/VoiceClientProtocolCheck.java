package dev.flybrain.voice;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the real client protocol dispatcher with a temporary cache, without a game or live network. */
public final class VoiceClientProtocolCheck {
    private static final UUID OWNER = UUID.randomUUID(), ID = UUID.randomUUID();
    private static final long GENERATION = 17;
    private static final byte[] WAV = VoiceAudio.wav(new short[48_000]);
    private static final String HASH = VoiceAudio.hash(WAV);
    public static void run() throws Exception {
        VoiceClient.initialize();
        var cache = new VoiceCache(Files.createTempDirectory("voice-client-protocol-"));
        var entry = new VoiceCache.Entry(OWNER, ID, WAV.length, HASH);
        invoke("clearState", new Class<?>[0]);
        var consent = (VoiceReceiveConsent)get("consent");
        accept(msg(VoiceMessage.STATE, OWNER, ID, 0, 1, "", new byte[0]));
        check(!(Boolean)get("receive"), "hostile state cannot invent local consent");
        accept(msg(VoiceMessage.RESET, OWNER, ID, 0, 0, "", new byte[0]));
        check(get("cache") == null, "no local cache created before consent");
        consent.command("flybrain voice receive on");
        expect(() -> accept(msg(VoiceMessage.STATE, OWNER, ID, 0, 2, "", new byte[0])));
        accept(msg(VoiceMessage.STATE, OWNER, ID, 0, 1, "", new byte[0]));
        accept(new VoiceMessage(VoiceMessage.MANIFEST, OWNER, ID, GENERATION - 1, 0, WAV.length, HASH, new byte[0]));
        check(((Map<?, ?>)get("manifest")).isEmpty(), "stale manifest ignored");
        expect(() -> accept(msg(VoiceMessage.MANIFEST, OWNER, ID, 0, WAV.length, HASH, new byte[0])));
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        var directory = loader.getClass().getDeclaredField("gameDir"); directory.setAccessible(true);
        Object oldDirectory = directory.get(loader);
        directory.set(loader, Files.createTempDirectory("voice-client-reset-"));
        try {
            accept(msg(VoiceMessage.RESET, OWNER, ID, 0, 0, "", new byte[0]));
            check(get("cache") != null, "consented RESET creates server-scoped cache");
        } finally { directory.set(loader, oldDirectory); }
        set("cache", cache);
        expect(() -> accept(msg(VoiceMessage.CHUNK, OWNER, ID, 0, WAV.length, HASH, new byte[]{1})));
        accept(msg(VoiceMessage.MANIFEST, OWNER, ID, 0, WAV.length, HASH, new byte[0]));
        expect(() -> accept(msg(VoiceMessage.MANIFEST, OWNER, ID, 0, WAV.length, HASH, new byte[0])));
        cache.put(entry, WAV);
        accept(VoiceMessage.control(VoiceMessage.MANIFEST_END, GENERATION));
        check((Boolean)get("manifestReady"), "complete manifest authorizes verified cache");
        expect(() -> accept(VoiceMessage.control(VoiceMessage.MANIFEST_END, GENERATION)));
        expect(() -> accept(msg(VoiceMessage.MANIFEST, OWNER, UUID.randomUUID(), 0, WAV.length, HASH, new byte[0])));
        set("active", entry); set("transfer", new VoiceAudio.Transfer(WAV.length, HASH));
        expect(() -> accept(msg(VoiceMessage.CHUNK, UUID.randomUUID(), ID, 0, WAV.length, HASH, new byte[]{1})));
        expect(() -> accept(msg(VoiceMessage.CHUNK, OWNER, UUID.randomUUID(), 0, WAV.length, HASH, new byte[]{1})));
        expect(() -> accept(msg(VoiceMessage.CHUNK, OWNER, ID, 0, WAV.length + 1, HASH, new byte[]{1})));
        expect(() -> accept(msg(VoiceMessage.CHUNK, OWNER, ID, 0, WAV.length, "0".repeat(64), new byte[]{1})));
        for (int offset = 0; offset < WAV.length; offset += VoiceAudio.CHUNK_BYTES)
            accept(msg(VoiceMessage.CHUNK, OWNER, ID, offset, WAV.length, HASH, Arrays.copyOfRange(WAV, offset, Math.min(WAV.length, offset + VoiceAudio.CHUNK_BYTES))));
        expect(() -> accept(msg(VoiceMessage.COMPLETE, OWNER, ID, WAV.length - 1, WAV.length, HASH, new byte[0])));
        accept(msg(VoiceMessage.COMPLETE, OWNER, ID, WAV.length, WAV.length, HASH, new byte[0]));
        check(get("active") == null && cache.contains(entry), "verified complete transfer published atomically");
        expect(() -> accept(msg(VoiceMessage.TOMBSTONE, UUID.randomUUID(), ID, 0, 0, "", new byte[0])));
        set("active", entry); set("transfer", new VoiceAudio.Transfer(WAV.length, HASH));
        accept(msg(VoiceMessage.TOMBSTONE, OWNER, ID, 0, 0, "", new byte[0]));
        check(get("active") == null && !cache.contains(entry), "tombstone cancels transfer and deletes file");
        accept(msg(VoiceMessage.TOMBSTONE, OWNER, UUID.randomUUID(), 0, 0, "", new byte[0]));
        expect(() -> accept(VoiceMessage.control(VoiceMessage.REQUEST, GENERATION)));
        set("manifestReady", false);
        set("serverIdentity", OWNER);
        expect(() -> accept(msg(VoiceMessage.RESET, UUID.randomUUID(), ID, 0, 0, "", new byte[0])));
        for (int i = 0; i < 60; i++) accept(msg(VoiceMessage.MANIFEST, OWNER, UUID.randomUUID(), 0, WAV.length, HASH, new byte[0]));
        expect(() -> accept(msg(VoiceMessage.MANIFEST, OWNER, UUID.randomUUID(), 0, WAV.length, HASH, new byte[0])));
        invoke("clearState", new Class<?>[0]);
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.invoker().onSendCommandMessage("flybrain voice receive on");
        check(consent.acceptServerState(true), "actual client command callback grants receive consent");
        set("cache", cache); cache.put(entry, WAV);
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.COMMAND.invoker().onSendCommandMessage("flybrain voice receive off");
        ((java.util.concurrent.ThreadPoolExecutor)get("IO")).submit(() -> {}).get(3, java.util.concurrent.TimeUnit.SECONDS);
        check(!consent.acceptServerState(true) && !cache.contains(entry), "client off callback revokes and clears managed cache");
        consent.command("flybrain voice receive on");
        invoke("reset", new Class<?>[0]);
        ((java.util.concurrent.ThreadPoolExecutor)get("IO")).submit(() -> {}).get(3, java.util.concurrent.TimeUnit.SECONDS);
        check(!consent.acceptServerState(true) && get("serverIdentity") == null, "client disconnect resets namespace and consent");
        System.out.println("VoiceClientProtocolCheck PASS: hostile state, stale generations, bounded/duplicate manifests, chunk identity/hash/offset, atomic completion, tombstones and receive revocation");
    }
    private static VoiceMessage msg(int kind, UUID owner, UUID id, int offset, int size, String hash, byte[] bytes) {
        return new VoiceMessage(kind, owner, id, GENERATION, offset, size, hash, bytes);
    }
    private static void accept(VoiceMessage message) throws Exception {
        invoke("accept", new Class<?>[]{VoiceMessage.class, String.class, long.class}, message, "synthetic-test", ((AtomicLong)get("connection")).get());
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        var method = VoiceClient.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }
    private static Object get(String name) throws Exception { var f = VoiceClient.class.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private static void set(String name, Object value) throws Exception { var f = VoiceClient.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expect(Checked body) throws Exception { try { body.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Malformed client transition accepted"); }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}

