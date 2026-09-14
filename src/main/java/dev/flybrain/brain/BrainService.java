package dev.flybrain.brain;

import dev.flybrain.config.BrainConfig;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** Latest-only mailboxes: no world access, waiting, or unbounded work queue on the server thread. */
public final class BrainService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("FlyBrain");
    public record Sample(BrainInput input, long submittedNanos) {}
    public record Result(BrainOutput output, long completedNanos, long submittedNanos) {}
    public record Diagnostics(boolean registered, Sample latestSample, Result lastResult) {}
    private static final class Slot {
        volatile Sample sample;
        volatile Result result;
        Slot(Sample sample) { this.sample = sample; }
    }
    private final ConcurrentHashMap<UUID, Slot> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "flybrain-neural"); thread.setDaemon(true); return thread;
    });
    private final BrainConfig config;
    private static final Gson JSON = new Gson();
    private volatile HttpURLConnection connection;
    private final ArrayBlockingQueue<String> telemetry = new ArrayBlockingQueue<>(128);
    public final Path sessionPath;
    public final AtomicLong droppedLogs = new AtomicLong();
    private long logBytes;
    private volatile boolean closed;
    public volatile long lastBatchNanos;
    public volatile String failure = "";
    public final AtomicLong messages = new AtomicLong();
    public final long startedNanos = System.nanoTime();

    public BrainService(BrainConfig config) {
        this(config, null);
    }
    public BrainService(BrainConfig config, Path sessionPath) {
        config.validate(); this.config = config;
        this.sessionPath = sessionPath;
        worker.scheduleAtFixedRate(this::batch, 0, 1_000_000_000L / config.brainHz, TimeUnit.NANOSECONDS);
    }

    /** Called only by the single Minecraft server thread. */
    public boolean submit(UUID id, BrainInput input) {
        if (closed) return false;
        Slot slot = slots.get(id);
        Sample sample = new Sample(input, System.nanoTime());
        if (slot == null) {
            if (slots.size() >= config.maxBrains) return false;
            slots.put(id, new Slot(sample));
        } else slot.sample = sample;
        return true;
    }

    private void batch() {
        if (closed) return;
        long start = System.nanoTime();
        var pending = new LinkedHashMap<String, Sample>();
        for (var entry : slots.entrySet()) {
            Sample sample = entry.getValue().sample;
            if (start - sample.submittedNanos() > TimeUnit.MILLISECONDS.toNanos(config.timeoutMs)) continue;
            pending.put(entry.getKey().toString(), sample);
        }
        if (!pending.isEmpty()) {
            try { exchange(pending); }
            catch (java.io.IOException | RuntimeException e) {
                slots.values().forEach(slot -> slot.result = null);
                String nextFailure = e.toString();
                if (!nextFailure.equals(failure)) LOG.warning("FlyWire offline; all motors stopped: " + nextFailure);
                failure = nextFailure;
            }
        }
        lastBatchNanos = System.nanoTime() - start;
        flushTelemetry();
    }

    private void exchange(LinkedHashMap<String, Sample> pending) throws java.io.IOException {
        var inputs = new LinkedHashMap<String, BrainInput>();
        pending.forEach((id, sample) -> inputs.put(id, sample.input()));
        byte[] request = JSON.toJson(java.util.Map.of("protocol", 1, "dtSeconds", 1.0 / config.brainHz,
                "samples", inputs)).getBytes(StandardCharsets.UTF_8);
        var http = (HttpURLConnection) URI.create("http://127.0.0.1:" + config.externalPort + "/step").toURL().openConnection();
        connection = http;
        try {
            http.setConnectTimeout(config.timeoutMs); http.setReadTimeout(config.timeoutMs);
            http.setInstanceFollowRedirects(false); http.setRequestMethod("POST"); http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json"); http.setFixedLengthStreamingMode(request.length);
            try (var out = http.getOutputStream()) { out.write(request); }
            if (http.getResponseCode() != 200) throw new java.io.IOException("Simulator HTTP " + http.getResponseCode());
            byte[] bytes;
            try (var in = http.getInputStream()) { bytes = in.readNBytes(65537); }
            if (bytes.length > 65536) throw new java.io.IOException("Oversized simulator response");
            var response = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (response.get("protocol").getAsInt() != 1 || !response.get("model").getAsString().equals("shiu-brian2-fafb783"))
                throw new java.io.IOException("Simulator is not the required FlyWire model");
            var outputs = response.getAsJsonObject("outputs");
            var validated = new LinkedHashMap<UUID, Result>();
            for (var entry : pending.entrySet()) {
                var json = outputs.getAsJsonObject(entry.getKey());
                for (String field : new String[]{"sequence", "forward", "turn", "ascend", "forwardHz", "backwardHz",
                        "leftHz", "rightHz", "escapeHz", "neurons", "connections", "computeNanos"})
                    if (json == null || !json.has(field)) throw new java.io.IOException("Incomplete motor response");
                var output = JSON.fromJson(json, BrainOutput.class);
                if (output.sequence() != entry.getValue().input().sequence() || output.neurons() == 0 || output.connections() == 0)
                    throw new java.io.IOException("Stale or empty-circuit output");
                validated.put(UUID.fromString(entry.getKey()), new Result(output, System.nanoTime(), entry.getValue().submittedNanos()));
            }
            if (!closed) validated.forEach((id, result) -> { Slot slot = slots.get(id); if (slot != null) slot.result = result; });
            messages.addAndGet(validated.size()); failure = "";
        } finally { http.disconnect(); connection = null; }
    }

    public boolean record(String json) {
        if (closed || sessionPath == null || json.length() > 32768 || !telemetry.offer(json)) {
            droppedLogs.incrementAndGet(); return false;
        }
        return true;
    }
    private void flushTelemetry() {
        if (sessionPath == null || telemetry.isEmpty()) return;
        try {
            Files.createDirectories(sessionPath.getParent());
            StringBuilder batch = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                String line = telemetry.poll(); if (line == null) break;
                if (logBytes + batch.length() + line.length() > 16_000_000) { droppedLogs.incrementAndGet(); continue; }
                batch.append(line).append('\n');
            }
            Files.writeString(sessionPath, batch, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logBytes += batch.length();
        } catch (java.io.IOException e) {
            failure = e.toString(); LOG.log(Level.SEVERE, "Session log write failed", e);
        }
    }

    public Result result(UUID id) {
        Slot slot = slots.get(id); Result result = slot == null ? null : slot.result;
        if (closed || result == null || System.nanoTime() - result.submittedNanos() > TimeUnit.MILLISECONDS.toNanos(config.timeoutMs)) return null;
        return result;
    }
    public int active() { return slots.size(); }
    /** Read-only telemetry may inspect expired results; actuators must continue using result(). */
    public Diagnostics diagnostics(UUID id) {
        Slot slot = slots.get(id);
        return slot == null ? new Diagnostics(false, null, null) : new Diagnostics(true, slot.sample, slot.result);
    }
    public void remove(UUID id) { slots.remove(id); }
    @Override public void close() {
        if (closed) return;
        closed = true; slots.clear();
        var http = connection; if (http != null) http.disconnect();
        worker.execute(this::flushTelemetry);
        worker.shutdown();
    }
}
