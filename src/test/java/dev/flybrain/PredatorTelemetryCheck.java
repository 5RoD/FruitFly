package dev.flybrain;

import dev.flybrain.debug.DebugLines;
import java.util.Map;

public final class PredatorTelemetryCheck {
    public static void main(String[] args) {
        var values=Map.<String,Object>of("gameBehavior","curious stalk","hunger",.75,"fear",.25,"curiosity",.6,"confidence",.4);
        var rows=DebugLines.rows("behavior",values);
        assert rows.size()==15;
        assert rows.contains("Game-AI drives, not neuron readings");
        assert rows.stream().anyMatch(s->s.equals("Hunger: 75%"));
        assert DebugLines.rows("motion",values).stream().noneMatch(s->s.startsWith("Applied forward")||s.contains("one impulse"));
        System.out.println("TELEMETRY PASS bounded behavior page separates game drives from neural measurements");
    }
}
