package dev.flybrain;

import dev.flybrain.model.*;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import java.nio.file.*;

/** Prescribed root transforms are renderer test inputs, not a runtime movement/brain controller. */
final class FootPlantCheck {
    static void check(GlbModel model) throws Exception {
        var seamPose=new GlbPose(model);
        for(String clip:java.util.List.of("idle","walk","run","air")) {
            double epsilon=clip.equals("air")?.00002:seamPose.duration(clip)*.001;
            seamPose.sample(clip,0,1); float[] first=seamPose.positions.clone();
            seamPose.sample(clip,seamPose.duration(clip)-epsilon,1);float[] last=seamPose.positions.clone();
            seamPose.sample(clip,epsilon,1);
            double gap=0;
            for(int i=0;i<first.length;i++) gap=Math.max(gap,Math.abs((first[i]-last[i])/epsilon-(seamPose.positions[i]-first[i])/epsilon));
            System.out.println("LOOP VELOCITY "+clip+" maximumComponentGap="+gap);
            assert gap<.15 : clip+" velocity seam "+gap;
        }
        var legs=com.google.gson.JsonParser.parseString(Files.readString(Path.of("docs/gait-refinement/leg-map.json")))
                .getAsJsonObject().getAsJsonObject("legs");
        // The renderer supplies real collision heights; exercise the same hook with a small step.
        for(String action:java.util.List.of("idle","groom")) {
            var pose=new GlbPose(model);var feet=new FootPlanting(model);
            feet.groundProbe(point->point.x<0?.025:0);
            double lifted=0,error=0;
            for(int frame=0;frame<192;frame++) {
                double t=frame/60.0;
                feet.update(new Matrix4d(),0,t,action,true);pose.sample(action,t,1,feet);
                for(var entry:legs.entrySet()) {
                    var ids=entry.getValue().getAsJsonObject().getAsJsonArray("contact_vertices");
                    var contact=new Vector3d();
                    for(var id:ids) {int i=id.getAsInt()*3;contact.add(pose.positions[i],pose.positions[i+1],pose.positions[i+2]);}
                    contact.div(ids.size());
                    if(action.equals("groom") && entry.getKey().endsWith("F")) lifted=Math.max(lifted,contact.y);
                    else error=Math.max(error,Math.abs(contact.y-(contact.x<0?.025:0)));
                }
            }
            assert error<.004 : action+" support-height error "+error;
            if(action.equals("groom"))assert lifted>.55 : "Groom forefeet stayed locked to the floor";
            System.out.println("SUPPORT PASS "+action+" stepError="+error+" forefootLift="+lifted);
        }
        var failures=new java.util.ArrayList<String>();
        for(String motion:java.util.List.of("spin","curve","backward","stop","reverse","restart","far")) {
            double maxAt60=0;
            for(boolean correction:new boolean[]{false,true}) {
              for(int fps:correction?new int[]{60,240}:new int[]{60}) {
                var pose=new GlbPose(model); var feet=new FootPlanting(model); var clock=new GaitClock();
                var previous=new java.util.HashMap<String,Vector3d>();
                double x=motion.equals("far")?10000000:0,z=0,angle=0,maxDrift=0,maxReach=0,maxStep=0; String clip="walk";
                Vector3d[] previousMesh=null;
                var preview=correction && fps==60 && motion.equals("curve")
                        ?java.nio.ByteBuffer.allocate(90*model.positions.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN):null;
                int worstFrame=0,worstVertex=0;
                for(int frame=0;frame<8*fps;frame++) {
                    double now=frame/(double)fps,dt=1.0/fps;
                    double forward=motion.equals("spin")?0:motion.equals("backward")?-.5:.5;
                    double turn=motion.equals("backward")?0:1.5;
                    if(motion.equals("stop") && now>3) {forward=0;turn=0;}
                    if(motion.equals("reverse") && now>3) {forward=-.5;turn=-1.5;}
                    if(motion.equals("restart") && now>2 && now<3) {forward=0;turn=0;}
                    if(frame%(fps/20)==0) clock.update(forward/20,turn/20,.05,false);
                    angle+=turn*dt; x+=Math.sin(angle)*forward*dt; z+=Math.cos(angle)*forward*dt;
                    String next=clock.speed()<.03?"idle":clock.running()?"run":"walk";
                    if(!clip.equals(next)) {pose.beginTransition();clip=next;}
                    double phase=clock.phase((frame%(fps/20))/(double)(fps/20));
                    var world=new Matrix4d().translation(x,0,z).rotateY(angle)
                            .translate(-(model.min.x+model.max.x)/2,-model.min.y,-(model.min.z+model.max.z)/2);
                    feet.update(world,phase,now,clip,true);
                    pose.sample(clip,clip.equals("idle")?now:phase*pose.duration(clip),1,correction?feet:null);
                    maxReach=Math.max(maxReach,feet.reachError());
                    var mesh=new Vector3d[model.positions.length/3];
                    for(int i=0;i<mesh.length;i++) {
                        mesh[i]=world.transformPosition(new Vector3d(pose.positions[i*3],pose.positions[i*3+1],pose.positions[i*3+2]));
                        if(previousMesh!=null && mesh[i].distance(previousMesh[i])>maxStep) {
                            maxStep=mesh[i].distance(previousMesh[i]);worstFrame=frame;worstVertex=i;
                        }
                    }
                    previousMesh=mesh;
                    if(correction && frame==fps*2) {
                        float[] displayed=pose.positions.clone();
                        pose.beginTransition(); pose.sample("air",0,0);
                        for(int i=0;i<displayed.length;i++) assert Math.abs(displayed[i]-pose.positions[i])<.00001 : "Corrected-pose transition snap";
                    }
                    if(preview!=null && frame<180 && frame%2==0)
                        for(var v:mesh) {preview.putFloat((float)v.x);preview.putFloat((float)v.y);preview.putFloat((float)v.z);}
                    double duty=clip.equals("run")?.56:.62;
                    for(var entry:legs.entrySet()) {
                        String name=entry.getKey(); var leg=entry.getValue().getAsJsonObject();
                        double p=phase+leg.get("phase").getAsDouble()+(name.endsWith("M")?.015:name.endsWith("H")?-.015:0);
                        p-=Math.floor(p);
                        boolean stance=clip.equals("idle") || p<duty;
                        var ids=leg.getAsJsonArray("contact_vertices"); var point=new Vector3d();
                        for(var id:ids) {int i=id.getAsInt()*3;point.add(pose.positions[i],pose.positions[i+1],pose.positions[i+2]);}
                        point.div(ids.size());world.transformPosition(point);
                        if(stance && frame>fps && !(motion.equals("stop") && now<3.5)) {
                            var anchor=previous.putIfAbsent(name,new Vector3d(point));
                            if(anchor!=null) maxDrift=Math.max(maxDrift,Math.hypot(point.x-anchor.x,point.z-anchor.z));
                        } else previous.remove(name);
                    }
                }
                System.out.println("WORLD FEET "+motion+" fps="+fps+" corrected="+correction+" maxStanceDrift="+maxDrift+" maxReachError="+maxReach+" maxVertexStep="+maxStep+" worstFrame="+worstFrame+" vertex="+worstVertex);
                if(correction) { if(maxDrift>=.003 || maxReach>=.002 || maxStep>=.25 || (fps==240 && maxStep>maxAt60*.4)) failures.add(motion+" drift="+maxDrift+" reach="+maxReach+" step="+maxStep); }
                else if(motion.equals("spin")) assert maxDrift>.03 : "Test did not reproduce turning slide";
                if(correction && fps==60) maxAt60=maxStep;
                if(preview!=null) Files.write(Path.of("docs/gait-refinement/runtime-curve.f32"),preview.array());
              }
            }
        }
        assert failures.isEmpty() : failures;
    }
}

