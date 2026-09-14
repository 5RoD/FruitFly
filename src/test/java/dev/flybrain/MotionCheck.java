package dev.flybrain;

import dev.flybrain.model.*;
import java.util.*;

final class MotionCheck {
    static void check(GlbModel model) {
        var pose=new GlbPose(model);
        for(int fps:new int[]{30,60,120,240}) {
            float[] last=null;double minimum=Double.POSITIVE_INFINITY;
            for(int f=0;f<fps;f++) {
                double t=f/(double)fps;
                pose.sample("air",t,1);var body=pose.positions.clone();pose.exposeWings(t,1);
                double movement=0,exposure=0;
                for(int i=0;i<body.length;i++) {
                    if(!pose.wingVertices[i/3]) assert body[i]==pose.positions[i] : "Wing pass moved the body";
                    else {
                        if(last!=null) movement=Math.max(movement,Math.abs(last[i]-pose.positions[i]));
                        exposure=Math.max(exposure,Math.abs(pose.wingExposure[0][i]-pose.wingExposure[1][i]));
                    }
                }
                if(last!=null)minimum=Math.min(minimum,movement);
                assert exposure>.1 : "Frozen shutter envelope";
                last=pose.positions.clone();
            }
            assert minimum>.02 : "Aliased frozen wings at "+fps;
            System.out.println("WINGS PASS fps="+fps+" minimumFrameMotion="+minimum);
        }
        for(String from:pose.clips()) for(String to:pose.clips()) {
            pose.sample(from,pose.duration(from)*.4,1);float[] before=pose.positions.clone();
            pose.beginTransition();pose.sample(to,0,0);
            for(int i=0;i<before.length;i++) assert Math.abs(before[i]-pose.positions[i])<.00002 : from+" -> "+to+" interrupted snap";
            for(int step=1;step<=10;step++) {
                pose.sample(to,step*.01,step/10f);
                for(float value:pose.positions)assert Float.isFinite(value);
            }
        }
        pose.sample("death",pose.duration("death"),1);var dead=pose.positions.clone();
        pose.sample("death",100,1);
        assert Arrays.equals(dead,pose.positions) : "Death restarts or loops";
        for(int i=1;i<dead.length;i+=3)assert dead[i]>-.001 : "Death floor penetration";
        System.out.println("MOTION PASS all clip-pair interruptions, stable death, isolated continuous wing exposure");
        for(String action:List.of("strike","hurt","land")) {
            var feet=new FootPlanting(model);double reach=0;
            for(int frame=0;frame<pose.duration(action)*120;frame++) {
                double t=frame/120.0;
                feet.update(new org.joml.Matrix4d().translation(0,0,t*2),t*2/.32,t,action,true);
                pose.sample(action,t,1,feet);reach=Math.max(reach,feet.reachError());
            }
            System.out.println("MOVING ACTION "+action+" maxReachError="+reach);
            assert reach<.02 : "Moving action loses support: "+action+" reach="+reach;
        }
    }
}
