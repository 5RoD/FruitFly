package dev.flybrain.brain;

/** Version-1 neural boundary: normalized signals, never game objects or target coordinates. */
public record BrainInput(long sequence, double visionLeft, double visionRight,
        double obstacleLeft, double obstacleRight, double brightness, double damage,
        boolean grounded) {
    public BrainInput {
        if (sequence < 0) throw new IllegalArgumentException("Negative sequence");
        for (double value : new double[]{visionLeft, visionRight, obstacleLeft, obstacleRight, brightness, damage})
            if (!Double.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException("Sensory signals must be finite and within [0,1]");
    }
}
