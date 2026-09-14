package dev.flybrain.config;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BrainConfig {
    public int brainHz = 4;
    public int sensoryHz = 10;
    public int maxBrains = 1;
    public int timeoutMs = 1000;
    public double sensoryRange = 16;
    public double speed = .12;
    public double turnDegreesPerTick = 6;
    public double jumpActivation = .6;
    public double modelScale = 1;
    public double modelYawDegrees = 0;
    public boolean debug = true;
    public boolean logging = false;
    public String simulationMode = "flywire-external";
    public int externalPort = 8765;
    public String fallback = "stop";
    public boolean brainAutoStart = true;
    public String brainPython = "../.venv-flywire/Scripts/python.exe";
    public String brainScript = "../brain-simulator/brain_server.py";
    public double ambienceVolume = .5, movementVolume = .65, scareVolume = .75;

    public static BrainConfig load(Path path) {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            BrainConfig config;
            if (Files.exists(path)) config = gson.fromJson(Files.readString(path), BrainConfig.class);
            else {
                config = new BrainConfig();
                Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(config));
            }
            if (config == null) throw new IllegalArgumentException("Empty configuration");
            if ("local-async".equals(config.simulationMode)) {
                config.simulationMode = "flywire-external";
                config.maxBrains = 1;
                config.brainHz = 4;
                config.timeoutMs = 1000;
                config.validate();
                Files.writeString(path, gson.toJson(config));
                org.slf4j.LoggerFactory.getLogger("FlyBrain").warn("Removed synthetic mode from legacy config; FlyWire process is now required");
            }
            config.validate();
            return config;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Invalid FlyBrain config at " + path + ": " + e.getMessage(), e);
        }
    }

    public void validate() {
        if (brainHz < 1 || brainHz > 4 || sensoryHz < 1 || sensoryHz > 20 || 20 % sensoryHz != 0
                || maxBrains != 1 || timeoutMs < 100 || timeoutMs > 10000 || externalPort < 1024 || externalPort > 65535
                || !range(sensoryRange, 1, 32) || !range(speed, 0, .4)
                || !range(turnDegreesPerTick, 0, 20) || !range(jumpActivation, .01, 1) || !range(modelScale, .1, 5)
                || !range(modelYawDegrees, -360, 360) || !range(ambienceVolume,0,1) || !range(movementVolume,0,1) || !range(scareVolume,0,1)
                || brainPython==null || brainPython.isBlank() || brainScript==null || brainScript.isBlank()
                || !"flywire-external".equals(simulationMode) || !"stop".equals(fallback))
            throw new IllegalArgumentException("Config values out of range; only flywire-external with stop-on-failure is supported");
    }
    private static boolean range(double x, double min, double max) { return Double.isFinite(x) && x >= min && x <= max; }
}
