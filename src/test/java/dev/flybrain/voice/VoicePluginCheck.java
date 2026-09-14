package dev.flybrain.voice;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.config.ConfigAccessor;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Synthetic Opus packets and a fake decoder only; never opens or records a real microphone. */
public final class VoicePluginCheck {
    private static final Map<Class<?>, Consumer<Object>> events = new HashMap<>();
    private static final UUID OWNER = UUID.randomUUID();
    private static final AtomicBoolean grouped = new AtomicBoolean(), recording = new AtomicBoolean(true), hold = new AtomicBoolean();
    private static final AtomicInteger decoded = new AtomicInteger(), decodedSamples = new AtomicInteger(960);
    private static final AtomicBoolean connected = new AtomicBoolean(true), installed = new AtomicBoolean(true), disabled = new AtomicBoolean(), missing = new AtomicBoolean(), groupObject = new AtomicBoolean();
    private static CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public static void main(String[] args) throws Exception {
        check(VoicePlugin.canMimic(true, false, "curious stalk"), "actual gameplay stalking state is eligible");
        check(!VoicePlugin.canMimic(true, true, "curious stalk"), "paused stalking never speaks");
        check(!VoicePlugin.canMimic(true, false, "feeding"), "feeding never speaks");
        check(!VoicePlugin.canMimic(false, false, "curious stalk") && !VoicePlugin.canMimic(true, false, null), "dead or unknown behavior never speaks");
        var library = new VoiceLibrary(Files.createTempDirectory("flybrain-fake-svc-")); VoiceIntegration.library = library;
        var player = proxy(ServerPlayer.class, (name, values) -> name.equals("getUuid") ? OWNER : null);
        var connection = proxy(VoicechatConnection.class, (name, values) -> switch (name) {
            case "getPlayer" -> player;
            case "isConnected" -> connected.get();
            case "isInstalled" -> installed.get();
            case "isDisabled" -> disabled.get();
            case "getGroup" -> groupObject.get() ? proxy(Group.class, (method, arguments) -> null) : null;
            case "isInGroup" -> grouped.get();
            default -> null;
        });
        var config = proxy(ConfigAccessor.class, (name, values) -> name.equals("getBoolean") ? recording.get() : null);
        var api = proxy(VoicechatServerApi.class, (name, values) -> switch (name) {
            case "getConnectionOf" -> missing.get() ? null : connection;
            case "getServerConfig" -> config;
            case "createDecoder" -> proxy(OpusDecoder.class, (method, args1) -> {
                if (!method.equals("decode")) return null;
                decoded.incrementAndGet();
                if (hold.get()) { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("decoder release timed out"); }
                return new short[decodedSamples.get()];
            });
            default -> null;
        });
        var plugin = new VoicePlugin();
        plugin.registerEvents(new EventRegistration() {
            @Override public <T extends Event> void registerEvent(Class<T> type, Consumer<T> consumer, int priority) {
                events.put(type, event -> consumer.accept(type.cast(event)));
            }
        });
        fire(VoicechatServerStartedEvent.class, proxy(VoicechatServerStartedEvent.class, (name, values) -> api));
        send(api, connection, 50); Thread.sleep(50);
        check(decoded.get() == 0 && library.list(OWNER).isEmpty(), "nonconsenting audio never decoded");
        library.start(OWNER, System.currentTimeMillis()); send(api, connection, 50);
        waitFor(() -> library.list(OWNER).size() == 1);
        check(decoded.get() == 50, "consenting packet stream decoded once");
        grouped.set(true); int before = decoded.get(); send(api, connection, 50); Thread.sleep(50);
        check(decoded.get() == before && library.list(OWNER).size() == 1, "grouped packets rejected before decode");
        grouped.set(false); recording.set(false); send(api, connection, 50); Thread.sleep(50);
        check(decoded.get() == before, "server recording toggle respected per packet");
        recording.set(true); hold.set(true);
        library.start(OWNER, System.currentTimeMillis()); send(api, connection, 50);
        check(entered.await(3, TimeUnit.SECONDS), "worker entered synthetic decode");
        VoiceIntegration.stopCapture(OWNER); release.countDown(); hold.set(false);
        Thread.sleep(200);
        check(library.list(OWNER).size() == 1, "stop during decode invalidates queued publication");
        library.start(OWNER, System.currentTimeMillis()); send(api, connection, 50);
        waitFor(() -> library.list(OWNER).size() == 2);
        fire(PlayerDisconnectedEvent.class, proxy(PlayerDisconnectedEvent.class, (name, values) -> name.equals("getPlayerUuid") ? OWNER : api));
        check(!library.capturing(OWNER, System.currentTimeMillis()), "disconnect resets capture consent");
        int priorDecode = decoded.get();
        fire(MicrophonePacketEvent.class, proxy(MicrophonePacketEvent.class, (name, values) -> name.equals("isCancelled") ? true : null));
        send(api, null, 1);
        VoiceIntegration.library = null; send(api, connection, 1); VoiceIntegration.library = library;
        library.start(OWNER, System.currentTimeMillis());
        for (var flag : new AtomicBoolean[]{missing, disabled, groupObject}) { flag.set(true); send(api, connection, 1); flag.set(false); }
        for (var flag : new AtomicBoolean[]{connected, installed}) { flag.set(false); send(api, connection, 1); flag.set(true); }
        raw(api, connection, null); raw(api, connection, new byte[4097]); send(api, connection, 0);
        check(decoded.get() == priorDecode, "cancelled/null/unavailable/disabled/private/malformed packets never decoded");
        library.start(OWNER, System.currentTimeMillis() - 1_800_001); send(api, connection, 1);
        check(decoded.get() == priorDecode, "expired capture never decodes");
        library.start(OWNER, System.currentTimeMillis()); send(api, connection, 1);
        waitFor(() -> decoded.get() > priorDecode); Thread.sleep(100);
        check(library.list(OWNER).size() == 2, "subsecond partial not published");
        VoiceIntegration.stopCapture(OWNER);
        entered = new CountDownLatch(1); release = new CountDownLatch(1); hold.set(true);
        library.start(OWNER, System.currentTimeMillis()); raw(api, connection, new byte[]{1});
        check(entered.await(3, TimeUnit.SECONDS), "overflow fixture holds decode worker");
        send(api, connection, 200);
        check(!library.capturing(OWNER, System.currentTimeMillis()), "bounded queue overflow stops capture");
        release.countDown(); hold.set(false); Thread.sleep(100);
        check(library.list(OWNER).size() == 2, "overflow drops partial rather than publishing");
        library.start(OWNER, System.currentTimeMillis());
        fire(PlayerConnectedEvent.class, proxy(PlayerConnectedEvent.class, (name, values) -> name.equals("getConnection") ? connection : api));
        check(!library.capturing(OWNER, System.currentTimeMillis()), "new SVC connection clears consent");
        library.start(OWNER, System.currentTimeMillis());
        fire(PlayerStateChangedEvent.class, proxy(PlayerStateChangedEvent.class, (name, values) -> switch (name) { case "getPlayerUuid" -> OWNER; case "isDisabled" -> true; default -> null; }));
        check(!library.capturing(OWNER, System.currentTimeMillis()), "disabled state stops capture");
        library.start(OWNER, System.currentTimeMillis());
        fire(PlayerStateChangedEvent.class, proxy(PlayerStateChangedEvent.class, (name, values) -> switch (name) { case "getPlayerUuid" -> OWNER; case "isDisconnected" -> true; default -> null; }));
        check(!library.capturing(OWNER, System.currentTimeMillis()), "disconnected state stops capture");
        fire(PlayerStateChangedEvent.class, proxy(PlayerStateChangedEvent.class, (name, values) -> null));
        library.start(OWNER, System.currentTimeMillis());
        fire(JoinGroupEvent.class, proxy(JoinGroupEvent.class, (name, values) -> name.equals("getConnection") ? connection : null));
        check(!library.capturing(OWNER, System.currentTimeMillis()), "join group invalidates partial capture");
        var muted = new AtomicBoolean(); VoiceIntegration.clientMute = muted::set;
        fire(MicrophoneMuteEvent.class, proxy(MicrophoneMuteEvent.class, (name, values) -> name.equals("isDisabled") ? true : null));
        check(muted.get(), "local mute event forwarded");
        muted.set(false); fire(ClientVoicechatConnectionEvent.class, proxy(ClientVoicechatConnectionEvent.class, (name, values) -> name.equals("isConnected") ? true : null));
        check(!muted.get(), "connected client event does not fake a mute");
        fire(ClientVoicechatConnectionEvent.class, proxy(ClientVoicechatConnectionEvent.class, (name, values) -> name.equals("isConnected") ? false : null));
        check(muted.get(), "client disconnect forwards stop");
        playbackRevocation(plugin, library.list(OWNER).getFirst());
        library.start(OWNER, System.currentTimeMillis()); decodedSamples.set(VoiceAudio.RATE + 1);
        raw(api, connection, new byte[]{1});
        waitFor(() -> !library.capturing(OWNER, System.currentTimeMillis()));
        check(library.list(OWNER).size() == 2, "invalid native decoder frame stops capture without publication");
        decodedSamples.set(VoiceAudio.RATE); library.start(OWNER, System.currentTimeMillis()); send(api, connection, 7);
        waitFor(() -> library.list(OWNER).size() == 4);
        check(library.list(OWNER).stream().anyMatch(c -> c.samples() == VoiceAudio.MAX_SAMPLES), "continuous speech splits at six seconds");
        check(library.samples(OWNER) == 9 * VoiceAudio.RATE, "six-second split preserves remaining one-second tail");
        VoiceIntegration.stopCapture(OWNER); decodedSamples.set(960);
        long capGeneration = library.start(OWNER, System.currentTimeMillis());
        for (int seconds = 9; seconds < 60; seconds++) check(library.save(OWNER, capGeneration, new short[48_000], System.currentTimeMillis()) != null, "prepare valid sixty-second library");
        int retained = library.list(OWNER).size(); send(api, connection, 50);
        waitFor(() -> !library.capturing(OWNER, System.currentTimeMillis()));
        check(library.list(OWNER).size() == retained && library.samples(OWNER) == 60 * VoiceAudio.RATE, "full-library capture stops without replacing saved clips");
        fire(VoicechatServerStoppedEvent.class, proxy(VoicechatServerStoppedEvent.class, (name, values) -> api));
        check(!VoiceIntegration.available.getAsBoolean(), "optional plugin stop closes availability");
        VoiceIntegration.library = null;
        System.out.println("VoicePluginCheck PASS: no-consent/group/server-policy rejection before decode, synthetic per-stream decode, stop-during-decode generation race, restart capture, disconnect reset, plugin shutdown");
    }
    private static void playbackRevocation(VoicePlugin plugin, VoiceLibrary.Clip clip) throws Exception {
        var type = Class.forName("dev.flybrain.voice.VoicePlugin$Playback"); var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object playback = constructor.newInstance();
        var stops = new AtomicInteger();
        var player = proxy(de.maxhenkel.voicechat.api.audiochannel.AudioPlayer.class, (name, values) -> {
            if (name.equals("stopPlaying")) stops.incrementAndGet();
            return name.equals("isStopped") ? false : null;
        });
        for (var entry : Map.of("player", player, "clip", clip, "eligible", true).entrySet()) { var field = type.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(playback, entry.getValue()); }
        var field = VoicePlugin.class.getDeclaredField("playbacks"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var plays = (Map<UUID, Object>)field.get(plugin); plays.put(UUID.randomUUID(), playback);
        VoiceIntegration.invalidate.accept(UUID.randomUUID()); check(stops.get() == 0, "another owner cannot stop active clip");
        VoiceIntegration.invalidate.accept(OWNER); VoiceIntegration.invalidate.accept(OWNER);
        check(stops.get() == 1, "own revocation stops active player exactly once");
        var cooldown = VoicePlugin.class.getDeclaredMethod("cooldown"); cooldown.setAccessible(true);
        for (int i = 0; i < 20; i++) { long delay = (Long)cooldown.invoke(null); check(delay >= 45_000 && delay <= 90_000, "mimic cooldown bounds"); }
    }
    private static void raw(VoicechatServerApi api, VoicechatConnection connection, byte[] bytes) {
        var packet = proxy(MicrophonePacket.class, (name, values) -> name.equals("getOpusEncodedData") ? bytes : null);
        fire(MicrophonePacketEvent.class, proxy(MicrophonePacketEvent.class, (name, values) -> switch (name) {
            case "getVoicechat" -> api;
            case "getSenderConnection" -> connection;
            case "getPacket" -> packet;
            default -> null;
        }));
    }
    private static void send(VoicechatServerApi api, VoicechatConnection connection, int frames) {
        for (int i = 0; i <= frames; i++) {
            byte[] bytes = i == frames ? new byte[0] : new byte[]{1};
            var packet = proxy(MicrophonePacket.class, (name, values) -> name.equals("getOpusEncodedData") ? bytes : null);
            fire(MicrophonePacketEvent.class, proxy(MicrophonePacketEvent.class, (name, values) -> switch (name) {
                case "getVoicechat" -> api;
                case "getSenderConnection" -> connection;
                case "getPacket" -> packet;
                default -> null;
            }));
        }
    }
    private static void fire(Class<?> type, Object event) { events.get(type).accept(event); }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        check(condition.getAsBoolean(), "asynchronous clip publication");
    }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    @FunctionalInterface private interface Calls { Object call(String name, Object[] arguments) throws Exception; }
    private static <T> T proxy(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, arguments) -> {
            Object value = calls.call(method.getName(), arguments);
            if (value == null && method.getReturnType() == boolean.class) return false;
            if (value == null && method.getReturnType() == int.class) return 0;
            return value;
        }));
    }
}



