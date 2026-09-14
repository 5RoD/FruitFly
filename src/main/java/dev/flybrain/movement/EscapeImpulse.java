package dev.flybrain.movement;

/** Actuator calibration: one grounded impulse per strong neural burst, not one jump per landing. */
public final class EscapeImpulse {
    private boolean armed=true;
    public double update(double drive, boolean grounded, double threshold) {
        if (!Double.isFinite(drive) || drive<0 || drive>1 || !Double.isFinite(threshold) || threshold<=0 || threshold>1)
            throw new IllegalArgumentException("Invalid escape actuator input");
        if (drive < threshold*.5) armed=true;
        if (drive < threshold || !armed) return 0;
        armed=false;
        return grounded ? .42*drive : 0;
    }
}
