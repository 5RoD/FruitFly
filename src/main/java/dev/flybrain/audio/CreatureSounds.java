package dev.flybrain.audio;

import net.minecraft.registry.*;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import java.util.*;

public final class CreatureSounds {
    private static final Map<String,SoundEvent> SOUNDS=new HashMap<>();
    private CreatureSounds(){}
    public static void initialize(){
        for(String name:List.of("idle","walk","run","groom","takeoff","air","land","strike","feeding","hurt","death","crawl","scare")) {
            var id=Identifier.of("flybrain",name);SOUNDS.put(name,Registry.register(Registries.SOUND_EVENT,id,SoundEvent.of(id)));
        }
    }
    public static SoundEvent get(String name){return Objects.requireNonNull(SOUNDS.get(name),name);}
}
