package dev.flybrain.brain;

/** Commands decoded from measured neuron spike rates by the external published model. */
public record BrainOutput(long sequence, double forward, double turn, double ascend,
        double forwardHz, double backwardHz, double leftHz, double rightHz, double escapeHz,
        int neurons, int connections, long computeNanos) {
    public static final BrainOutput STOP = new BrainOutput(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    public BrainOutput {
        for (double value : new double[]{forward, turn, ascend})
            if (!Double.isFinite(value) || Math.abs(value) > 1)
                throw new IllegalArgumentException("Invalid neural output");
        for (double value : new double[]{forwardHz, backwardHz, leftHz, rightHz, escapeHz})
            if (!Double.isFinite(value) || value < 0 || value > 10000)
                throw new IllegalArgumentException("Invalid firing rate");
        if (sequence < 0 || computeNanos < 0 || neurons < 0 || connections < 0)
            throw new IllegalArgumentException("Invalid output metadata");
    }
}
