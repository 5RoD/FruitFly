package dev.flybrain;

import dev.flybrain.model.*;
import org.joml.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Renderer fixtures only. Prescribed trajectories here never run on an entity or a neural server. */
public final class AnimationReel {
    public static void main(String[] args) throws Exception {
        var root=Path.of("docs/macro-animation"); Files.createDirectories(root);
        var model=new GlbModel(Files.readAllBytes(Path.of("src/main/resources/assets/flybrain/models/creature-animated.glb")));
        var timeline=new ArrayList<Map<String,Object>>();
        int index=0,fps=30;
        try(var output=FileChannel.open(root.resolve("reel.f32"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            var buffer=ByteBuffer.allocate((model.positions.length*3+3)*4).order(ByteOrder.LITTLE_ENDIAN);
            var clips=new ArrayList<>(new GlbPose(model).clips());
            clips.addAll(List.of("start-stop-reverse","tight-turn","interruptions","flight-trajectory","wing-slow"));
            for(String scenario:clips) for(double rate:scenario.equals("wing-slow")?new double[]{1.0/32}:new double[]{1,.25}) {
                var pose=new GlbPose(model);var feet=new FootPlanting(model);var gait=new GaitClock();
                boolean fixture=!pose.clips().contains(scenario);
                double duration=fixture?scenario.equals("wing-slow")?.12:4:pose.duration(scenario);
                if(scenario.equals("idle") && rate<1) duration=1.1; // Detail excerpt around the alert head movement.
                if(scenario.equals("walk") || scenario.equals("run") || scenario.equals("air")) duration*=2;
                double display=duration/rate;
                int count=(int)java.lang.Math.ceil(display*fps);
                timeline.add(Map.of("name",scenario,"rate",rate,"startFrame",index,"frames",count,"sourceSeconds",duration));
                double x=0,z=0,angle=0,phase=0,wing=0,change=0;
                String clip=fixture?"idle":scenario;
                pose.sample(clip,0,1);
                for(int frame=0;frame<count;frame++) {
                    double t=frame*rate/fps,dt=rate/fps;
                    double forward=0,turn=0,height=0;boolean ground=true;
                    String next=clip;
                    if(!fixture) {
                        next=scenario;
                        if(scenario.equals("walk")) forward=.32/pose.duration("walk");
                        if(scenario.equals("run")) forward=.44/pose.duration("run");
                        ground=!(scenario.equals("air") || scenario.equals("takeoff"));
                    } else switch(scenario) {
                        case "start-stop-reverse" -> {forward=t<1?t*1.5:t<1.8?1.5:t<2.2?0:t<3?-1:0;next=forward==0?"idle":"walk";}
                        case "tight-turn" -> {forward=.4;turn=t<2?1.4:-1.4;next="walk";}
                        case "interruptions" -> next=t<.7?"walk":t<.85?"strike":t<1.1?"hurt":t<1.3?"strike":t<1.6?"hurt":t<2.6?"groom":"walk";
                        case "flight-trajectory" -> {
                            ground=t<.6 || t>2;
                            height=ground?0:1.2*java.lang.Math.sin(java.lang.Math.PI*(t-.6)/1.4);
                            next=t<.6?"idle":t<.9?"takeoff":t<2?"air":t<2.45?"land":"idle";
                        }
                        case "wing-slow" -> {next="air";ground=false;}
                        default -> throw new IllegalArgumentException(scenario);
                    }
                    if(!next.equals(clip)) {pose.beginTransition();clip=next;change=t;}
                    angle+=turn*dt;x+=java.lang.Math.sin(angle)*forward*dt;z+=java.lang.Math.cos(angle)*forward*dt;
                    gait.update(forward*dt,turn*dt,dt,false);phase=gait.phase(1);
                    var world=new Matrix4d().translation(x,height,z).rotateY(angle);
                    feet.update(world,phase,t,clip,ground);
                    double seconds=clip.equals("walk") || clip.equals("run")?phase*pose.duration(clip):t-change;
                    if(scenario.equals("idle") && rate<1) seconds+=1;
                    float blend=fixture?(float)java.lang.Math.clamp((t-change)/.12,0,1):1;
                    pose.sample(clip,seconds,blend,feet);
                    double target=ground?0:1;
                    wing+=(target-wing)*-java.lang.Math.expm1(-dt/(target>0?.045:.07));
                    pose.exposeWings(t,(float)wing);
                    buffer.clear();buffer.putFloat((float)wing);buffer.putFloat((float)x);buffer.putFloat((float)z);
                    for(float[] positions:new float[][]{pose.positions,pose.wingExposure[0],pose.wingExposure[1]}) for(int v=0;v<positions.length;v+=3) {
                        var point=new Vector3d(positions[v],positions[v+1],positions[v+2]);
                        // Follow root translation but retain actual heading, height and world contact motion.
                        world.transformPosition(point);point.x-=x;point.z-=z;
                        if(!point.isFinite()) throw new AssertionError("Nonfinite preview "+scenario);
                        buffer.putFloat((float)point.x);buffer.putFloat((float)point.y);buffer.putFloat((float)point.z);
                    }
                    buffer.flip();while(buffer.hasRemaining())output.write(buffer);
                    index++;
                }
            }
        }
        Files.writeString(root.resolve("reel.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("fps",fps,"frames",index,"vertices",model.positions.length/3,"timeline",timeline)));
        System.out.println("REEL PASS "+index+" frames; all clips plus five motion fixtures; normal and slow playback");
    }
}
