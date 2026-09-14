package dev.flybrain;

import dev.flybrain.audio.SoundCueClock;

public final class AudioCheck {
    public static void main(String[] args) {
        var cues=new SoundCueClock();
        assert cues.update("idle",0,false,1).isEmpty();
        assert cues.update("walk",0,false,2).isEmpty();
        assert cues.update("walk",1,false,3).equals(java.util.List.of("walk"));
        assert cues.update("walk",1,false,3).isEmpty();
        assert cues.update("takeoff",1,false,4).equals(java.util.List.of("takeoff"));
        assert cues.update("feeding",1,true,4).isEmpty();
        assert cues.update("feeding",1,false,5).equals(java.util.List.of("feeding"));
        assert cues.update("death",1,false,6).equals(java.util.List.of("death"));
        assert cues.update("death",1,false,7).isEmpty();
        System.out.println("AUDIO CUES PASS contact edges, one-shots, pause, death and duplicate suppression");
    }
}
