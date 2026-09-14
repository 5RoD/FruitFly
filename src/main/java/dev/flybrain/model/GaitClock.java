package dev.flybrain.model;

/** Distance-locked visual phase, interpolated over the same previous/current tick as the entity. */
public final class GaitClock {
    private double phase, previousPhase, speed;
    private boolean running;
    /** Promote before addition: float age arithmetic quantizes sub-ticks in long sessions. */
    public static double seconds(int age,float partial) {
        if(!Float.isFinite(partial))throw new IllegalArgumentException("Invalid partial tick");
        return (age+(double)Math.clamp(partial,0,1))/20.0;
    }
    public void update(double signedDistance, double seconds, boolean teleport) {
        update(signedDistance,0,seconds,teleport);
    }
    public void update(double signedDistance, double yawRadians, double seconds, boolean teleport) {
        if (!Double.isFinite(signedDistance) || !Double.isFinite(yawRadians) || !Double.isFinite(seconds) || seconds<=0)
            throw new IllegalArgumentException("Invalid gait measurement");
        previousPhase=phase;
        if (teleport) { speed=0; running=false; return; }
        // Turning moves the feet even when the entity's centre stays still.
        signedDistance=Math.copySign(Math.abs(signedDistance)+.75*Math.abs(yawRadians),signedDistance==0?1:signedDistance);
        speed+=(Math.abs(signedDistance)/seconds-speed)*-Math.expm1(-seconds/.1);
        if (running && speed<1.65) running=false;
        else if (!running && speed>2.0) running=true;
        double stride=running ? .44 : .32; // Matches authored cycleDistance, not stance excursion.
        phase+=signedDistance/stride;
    }
    public double phase(double partial) {
        if (!Double.isFinite(partial)) throw new IllegalArgumentException("Invalid partial tick");
        return previousPhase+(phase-previousPhase)*Math.clamp(partial,0,1);
    }
    public double speed() { return speed; }
    public boolean running() { return running; }
}
