package dev.flybrain.audio;

import java.util.*;

/** Edge-triggered audio decisions; paused clocks cannot produce repeated contact or action sounds. */
public final class SoundCueClock {
    private String previous="idle";
    private int contacts,age=-1;
    public List<String> update(String clip,int contactCount,boolean paused,int tick) {
        if(paused || tick==age)return List.of();
        age=tick;var cues=new ArrayList<String>(2);
        if(!clip.equals(previous) && Set.of("takeoff","land","strike","feeding","hurt","death","groom","scare").contains(clip))cues.add(clip);
        if(contactCount>contacts && Set.of("walk","run","crawl").contains(clip))cues.add(clip);
        if(clip.equals("idle") && tick%173==0)cues.add("idle");
        previous=clip;contacts=contactCount;return List.copyOf(cues);
    }
}
