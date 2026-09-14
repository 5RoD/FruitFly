package dev.flybrain.debug;

import dev.flybrain.brain.BrainInput;
import dev.flybrain.brain.BrainOutput;
import java.util.*;

/** Plain telemetry formatting, independently testable without a Minecraft client. */
public final class DebugLines {
    public static final List<String> PAGES = List.of("overview", "sensors", "neurons", "motion", "performance", "behavior");
    private DebugLines() {}
    private static boolean yes(Map<String,Object> v, String key) { return Boolean.TRUE.equals(v.get(key)); }
    private static double n(Map<String,Object> v, String key) { return v.get(key) instanceof Number x ? x.doubleValue() : 0; }
    private static String text(Map<String,Object> v, String key) { return Objects.toString(v.get(key), "-"); }
    private static String f(String format, Object... args) { return String.format(Locale.ROOT, format, args); }
    private static String ms(Map<String,Object> v, String key) { return v.get(key) == null ? "-" : f("%.0f", n(v,key)); }

    public static String state(Map<String,Object> v) {
        if (v.get("entity") == null) return "NO FLY";
        if (!yes(v,"alive")) return "DEAD";
        if (!yes(v,"serviceRunning")) return "STOPPED";
        if (!yes(v,"registered")) return n(v,"activeBrains") >= n(v,"maxBrains") ? "NO SLOT" : "WAITING";
        if (!yes(v,"hasResult")) return v.get("lastFailure") != null && !text(v,"lastFailure").isBlank() ? "ERROR" : "WAITING";
        return n(v,"resultAgeMs") > n(v,"timeoutMs") ? "STALE" : "ONLINE";
    }

    public static String reason(Map<String,Object> v) {
        String state = state(v);
        if (!state.equals("ONLINE")) return switch (state) {
            case "NO FLY" -> "Fly missing/unloaded; use /flybrain watch";
            case "NO SLOT" -> "Another fly owns the single brain slot";
            case "STALE" -> "Output expired; commanded motors stop";
            case "ERROR" -> "No valid result; inspect lastFailure";
            case "STOPPED" -> "Neural service is stopped";
            case "DEAD" -> "Tracked creature is dead";
            default -> "Waiting for first valid neural result";
        };
        if(v.containsKey("gameBehavior"))return "AI: "+text(v,"gameBehavior")+" / "+text(v,"traversal")+(yes(v,"animationPaused")?" (paused)":"");
        var motor = (BrainOutput)v.getOrDefault("motorAndDrives", BrainOutput.STOP);
        var input = (BrainInput)v.get("input");
        if (yes(v,"noAi")) return "NoAI disables normal travel";
        if (yes(v,"horizontalCollision") && Math.abs(motor.forward()) > .02) return "Forward drive; horizontal collision";
        if (n(v,"displacementBps") > .03) return "Measured ground displacement";
        if (Math.abs(motor.forward()) > .02) return "Forward drive present; check motion";
        if (input == null) return "Waiting for sensory sample";
        if (input.visionLeft() + input.visionRight() < .01) return "No visible living target in visual cone";
        var rates = (BrainOutput)v.getOrDefault("lastNeuralOutput", BrainOutput.STOP);
        if (rates.forwardHz() + rates.backwardHz() < .01) return "Visual input; no forward/backward spikes";
        return "Forward/backward drive cancels or is zero";
    }

    public static List<String> rows(String page, Map<String,Object> v) {
        if (!PAGES.contains(page)) throw new IllegalArgumentException("Unknown debug page: " + page);
        var rows = new ArrayList<String>();
        rows.add("State: " + state(v));
        String id = text(v,v.get("entity") == null ? "trackedEntity" : "entity");
        rows.add("Fly: " + id.substring(0,Math.min(8,id.length())) + " | " + page);
        rows.add("Why: " + reason(v));
        var input = (BrainInput)v.get("input");
        var motor = (BrainOutput)v.getOrDefault("motorAndDrives", BrainOutput.STOP);
        var rates = (BrainOutput)v.getOrDefault("lastNeuralOutput", BrainOutput.STOP);
        switch (page) {
            case "behavior" -> {
                rows.add("Game-AI drives, not neuron readings");
                rows.add("Decision: " + text(v,"gameBehavior"));
                rows.add("Traversal: " + text(v,"traversal"));
                rows.add(f("Hunger: %.0f%%",n(v,"hunger")*100));
                rows.add(f("Fear: %.0f%%",n(v,"fear")*100));
                rows.add(f("Curiosity: %.0f%%",n(v,"curiosity")*100));
                rows.add(f("Confidence: %.0f%%",n(v,"confidence")*100));
                rows.add("Triggered hunt: " + yes(v,"sceneActive"));
                rows.add("Feeding: " + yes(v,"feeding"));
                rows.add(f("Growth: %.2fx",n(v,"growthScale")));
                rows.add("Frozen: " + yes(v,"animationPaused"));
                rows.add("Brain: " + text(v,"brainProcess"));
            }
            case "overview" -> {
                rows.add(input == null ? "Vision L/R: -" : f("Vision L/R: %.0f%% / %.0f%%",input.visionLeft()*100,input.visionRight()*100));
                rows.add(input == null ? "Obstacle L/R: -" : f("Obstacle L/R: %.0f%% / %.0f%%",input.obstacleLeft()*100,input.obstacleRight()*100));
                rows.add(f("Last Hz F/B: %.1f / %.1f",rates.forwardHz(),rates.backwardHz()));
                rows.add(f("Last Hz L/R: %.1f / %.1f",rates.leftHz(),rates.rightHz()));
                rows.add(f("Drive F/T/U: %.2f / %.2f / %.2f",motor.forward(),motor.turn(),motor.ascend()));
                rows.add(f("Displacement: %.2f b/s",n(v,"displacementBps")));
                rows.add("Ground: " + yes(v,"grounded") + " | Wall: " + yes(v,"horizontalCollision"));
                rows.add("Result age / limit: " + ms(v,"resultAgeMs") + " / " + ms(v,"timeoutMs") + " ms");
                rows.add("Latency / compute: " + ms(v,"latencyMs") + " / " + ms(v,"computeMs") + " ms");
                rows.add(f("Circuit: %d neurons / %d edges",rates.neurons(),rates.connections()));
                rows.add(f("Brain slots: %.0f/%.0f | MSPT: %.1f",n(v,"activeBrains"),n(v,"maxBrains"),n(v,"serverMspt")));
            }
            case "sensors" -> {
                rows.add(input == null ? "Latest input: none" : "Latest input sequence: " + input.sequence());
                rows.add("Latest input age: " + ms(v,"inputAgeMs") + " ms");
                rows.add(f("Sensory cadence: %.0f Hz",n(v,"sensoryHz")));
                rows.add(f("Range / cone: %.1f blocks / 120 deg",n(v,"sensoryRange")));
                if (input != null) {
                    rows.add(f("Vision left: %.0f%%",input.visionLeft()*100));
                    rows.add(f("Vision right: %.0f%%",input.visionRight()*100));
                    rows.add(f("Obstacle left: %.0f%%",input.obstacleLeft()*100));
                    rows.add(f("Obstacle right: %.0f%%",input.obstacleRight()*100));
                    rows.add(f("Brightness: %.0f%% | Damage: %.0f%%",input.brightness()*100,input.damage()*100));
                    rows.add("Input grounded: " + input.grounded());
                }
                rows.add("Latest senses != older output input");
                rows.add("Vision: living, non-spectator, LOS");
            }
            case "neurons" -> {
                rows.add("Last result sequence: " + rates.sequence());
                rows.add("Result age: " + ms(v,"resultAgeMs") + " ms (stale is not applied)");
                rows.add(f("Forward population: %.1f Hz",rates.forwardHz()));
                rows.add(f("Backward population: %.1f Hz",rates.backwardHz()));
                rows.add(f("Left population: %.1f Hz",rates.leftHz()));
                rows.add(f("Right population: %.1f Hz",rates.rightHz()));
                rows.add(f("Escape population: %.1f Hz",rates.escapeHz()));
                rows.add(f("Neurons: %d | Connections: %d",rates.neurons(),rates.connections()));
                rows.add("Compute: " + ms(v,"computeMs") + " ms | FAFB v783");
                rows.add("Published Shiu/Brian2 dynamics");
                rows.add("Population rates, not individual spikes");
                rows.add("No thoughts/attack decisions inferred");
            }
            case "motion" -> {
                rows.add(f("Neural forward modulation: %+.3f",motor.forward()));
                rows.add(f("Neural turn readout: %+.3f",motor.turn()));
                rows.add(f("Neural escape modulation: %.2f",motor.ascend()));
                rows.add(f("Displacement: %.3f blocks/s",n(v,"displacementBps")));
                rows.add(f("Velocity X/Y/Z: %.2f / %.2f / %.2f",n(v,"velocityX"),n(v,"velocityY"),n(v,"velocityZ")));
                rows.add(f("Position: %.1f / %.1f / %.1f",n(v,"x"),n(v,"y"),n(v,"z")));
                rows.add(f("Yaw: %.1f | Health: %.1f",n(v,"yaw"),n(v,"health")));
                rows.add("Ground: " + yes(v,"grounded") + " | Wall: " + yes(v,"horizontalCollision"));
                rows.add("Vertical hit: " + yes(v,"verticalCollision") + " | NoAI: " + yes(v,"noAi"));
                rows.add("Game traversal: " + text(v,"traversal"));
                rows.add("AI owns routes; neural output modulates");
                rows.add("Velocity is not actual displacement");
            }
            case "performance" -> {
                rows.add(f("Brain / sensors: %.0f / %.0f Hz",n(v,"brainHz"),n(v,"sensoryHz")));
                rows.add(f("Brain slots: %.0f / %.0f",n(v,"activeBrains"),n(v,"maxBrains")));
                rows.add("Registered: " + yes(v,"registered"));
                rows.add("Input / result age: " + ms(v,"inputAgeMs") + " / " + ms(v,"resultAgeMs") + " ms");
                rows.add("Latency / compute: " + ms(v,"latencyMs") + " / " + ms(v,"computeMs") + " ms");
                rows.add("Timeout: " + ms(v,"timeoutMs") + " ms");
                rows.add(f("Worker batch: %.1f ms",n(v,"batchMs")));
                rows.add(f("Replies total / avg Hz: %.0f / %.2f",n(v,"messages"),n(v,"messagesPerSecond")));
                rows.add(f("Server MSPT / est TPS: %.1f / %.1f",n(v,"serverMspt"),n(v,"estimatedTps")));
                rows.add(f("Dropped logs: %.0f",n(v,"droppedLogs")));
                rows.add("Brain endpoint: 127.0.0.1:" + f("%.0f",n(v,"externalPort")));
            }
        }
        if (rows.size() < 15 && !Objects.toString(v.get("lastFailure"), "").isBlank()) rows.add("Error: " + text(v,"lastFailure"));
        while (rows.size() < 15) rows.add("");
        return rows.subList(0,15).stream().map(line -> {
            String clean = line.replaceAll("[\\p{Cntrl}\u00a7]", " ");
            return clean.length() > 72 ? clean.substring(0,69) + "..." : clean;
        }).toList();
    }
}
