package dev.flybrain.voice;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real snapshot/revocation transitions; no transport mock or live server required. */
public final class VoiceServerProtocolCheck {
    public static void run() throws Exception {
        VoiceIntegration.initialize();
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource>();
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.invoker().register(dispatcher, null, null);
        var voice = dispatcher.getRoot().getChild("flybrain").getChild("voice");
        for (String name : new String[]{"start", "stop", "status", "list", "delete", "clear", "share", "receive"})
            check(voice.getChild(name) != null, "self-only command registered: " + name);
        UUID owner = UUID.randomUUID(), receiver = UUID.randomUUID();
        var library = new VoiceLibrary(Files.createTempDirectory("voice-server-protocol-")); VoiceIntegration.library = library;
        long generation = library.start(owner, 1);
        var clip = library.save(owner, generation, new short[48_000], 2);
        var receives = field(VoiceIntegration.class, "receiving");
        @SuppressWarnings("unchecked") var receiveMap = (Map<UUID, Boolean>)receives.get(null);
        var stateType = Class.forName("dev.flybrain.voice.VoiceSync$Session");
        var constructor = stateType.getDeclaredConstructor(); constructor.setAccessible(true); Object session = constructor.newInstance();
        snapshot(stateType, session, receiver, library, false);
        check(messages(session).stream().noneMatch(m -> m.kind() == VoiceMessage.MANIFEST), "receive off sends no clip metadata");
        receiveMap.put(receiver, true);
        snapshot(stateType, session, receiver, library, true);
        check(messages(session).stream().noneMatch(m -> m.kind() == VoiceMessage.MANIFEST), "sharing off hides another owner's clips");
        receiveMap.put(owner, true);
        snapshot(stateType, session, owner, library, true);
        check(messages(session).stream().anyMatch(m -> m.kind() == VoiceMessage.MANIFEST && m.clip().equals(clip.id())), "owner receives own permitted clip");
        library.share(owner, true);
        snapshot(stateType, session, receiver, library, true);
        check(messages(session).stream().anyMatch(m -> m.kind() == VoiceMessage.MANIFEST), "sharing grant exposes authorized manifest");
        @SuppressWarnings("unchecked") var requestSessions = (Map<UUID, Object>)field(VoiceSync.class, "sessions").get(null);
        requestSessions.put(receiver, session);
        long requestGeneration = (Long)field(stateType, "generation").get(session);
        var request = new VoiceMessage(VoiceMessage.REQUEST, owner, clip.id(), requestGeneration, 0, 0, "", new byte[0]);
        VoiceSync.request(receiver, request); check(field(stateType, "load").get(session) == null, "request before manifest end rejected");
        field(stateType, "ready").set(session, true);
        VoiceSync.request(receiver, VoiceMessage.control(VoiceMessage.CHUNK, requestGeneration));
        check(field(stateType, "load").get(session) == null, "client uploads/wrong direction rejected");
        VoiceSync.request(UUID.randomUUID(), request); check(field(stateType, "load").get(session) == null, "unknown receiver rejected");
        VoiceSync.request(receiver, new VoiceMessage(VoiceMessage.REQUEST, owner, clip.id(), requestGeneration - 1, 0, 0, "", new byte[0]));
        check(field(stateType, "load").get(session) == null, "stale request generation rejected");
        receiveMap.put(receiver, false); VoiceSync.request(receiver, request); receiveMap.put(receiver, true);
        check(field(stateType, "load").get(session) == null, "receive revocation rejects request");
        library.share(owner, false); VoiceSync.request(receiver, request); library.share(owner, true);
        check(field(stateType, "load").get(session) == null, "spoofed owner field does not bypass share grant");
        field(stateType, "lastRequest").set(session, 0L);
        VoiceSync.request(receiver, new VoiceMessage(VoiceMessage.REQUEST, owner, UUID.randomUUID(), requestGeneration, 0, 0, "", new byte[0]));
        check(field(stateType, "load").get(session) == null, "unpublished clip request rejected");
        field(stateType, "lastRequest").set(session, 0L);
        VoiceSync.request(receiver, request);
        @SuppressWarnings("unchecked") var download = (CompletableFuture<byte[]>)field(stateType, "load").get(session);
        check(java.util.Arrays.equals(download.get(3, java.util.concurrent.TimeUnit.SECONDS), VoiceAudio.wav(new short[48_000])), "authorized request loads verified bytes asynchronously");
        VoiceSync.request(receiver, request); check(field(stateType, "load").get(session) == download, "overlapping request rejected");
        field(stateType, "clip").set(session, null); field(stateType, "load").set(session, null);
        field(stateType, "lastRequest").set(session, System.currentTimeMillis());
        VoiceSync.request(receiver, request); check(field(stateType, "load").get(session) == null, "request rate limit enforced");
        library.start(receiver, System.currentTimeMillis()); VoiceSync.request(receiver, VoiceMessage.control(VoiceMessage.MUTE, 0));
        check(!library.capturing(receiver, System.currentTimeMillis()), "authenticated mute stops only sender capture");
        var future = new CompletableFuture<byte[]>(); field(stateType, "load").set(session, future);
        library.revoke(owner, clip.id());
        snapshot(stateType, session, receiver, library, true);
        check(future.isCancelled(), "revocation cancels in-flight load");
        check(messages(session).stream().anyMatch(m -> m.kind() == VoiceMessage.TOMBSTONE && m.clip().equals(clip.id())), "revocation emits tombstone");
        @SuppressWarnings("unchecked") var sessions = (Map<UUID, Object>)field(VoiceSync.class, "sessions").get(null);
        sessions.put(receiver, session);
        var next = new CompletableFuture<byte[]>(); field(stateType, "load").set(session, next);
        VoiceSync.reset(receiver);
        check(next.isCancelled() && !sessions.containsKey(receiver), "disconnect reset cancels pending download");
        sessions.put(receiver, session); var last = new CompletableFuture<byte[]>(); field(stateType, "load").set(session, last);
        VoiceSync.clear(); check(last.isCancelled() && sessions.isEmpty(), "server shutdown cancels downloads");
        VoiceIntegration.resetConnection(owner); check(!VoiceIntegration.receiving(owner), "connection reset clears receive grant");
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(null);
        check(VoiceIntegration.library == null, "server lifecycle clears active library");
        System.out.println("VoiceServerProtocolCheck PASS: optional bootstrap/commands, own/shared/private manifests, tombstones, load cancellation, connection and server shutdown");
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    @SuppressWarnings("unchecked") private static ArrayDeque<VoiceMessage> messages(Object session) throws Exception { return (ArrayDeque<VoiceMessage>)field(session.getClass(), "pending").get(session); }
    private static void snapshot(Class<?> type, Object session, UUID receiver, VoiceLibrary library, boolean receive) throws Exception {
        var method = VoiceSync.class.getDeclaredMethod("snapshot", type, UUID.class, VoiceLibrary.class, boolean.class); method.setAccessible(true);
        try { method.invoke(null, session, receiver, library, receive); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}

