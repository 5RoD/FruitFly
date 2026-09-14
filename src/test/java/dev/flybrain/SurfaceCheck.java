package dev.flybrain;

import dev.flybrain.model.*;
import org.joml.*;
import java.nio.file.*;

/** The same planted motion must work on floor, wall and ceiling without changing local anatomy. */
public final class SurfaceCheck {
    public static void main(String[] args) throws Exception {
        var model=new GlbModel(Files.readAllBytes(Path.of("src/main/resources/assets/flybrain/models/creature-animated.glb")));
        for(double angle:new double[]{java.lang.Math.PI/2,java.lang.Math.PI}) {
            var base=new GlbPose(model);var rotated=new GlbPose(model);
            var a=new FootPlanting(model);var b=new FootPlanting(model);double error=0;
            for(int frame=0;frame<180;frame++) {
                double t=frame/120.0,phase=t/.65;
                var floor=new Matrix4d().translation(0,0,t*.32/.65);
                var wall=new Matrix4d().rotateZ(angle).mul(floor);
                a.update(floor,phase,t,"walk",true);b.update(wall,phase,t,"walk",true);
                base.sample("walk",t,1,a);rotated.sample("walk",t,1,b);
                for(int i=0;i<base.positions.length;i++)error=java.lang.Math.max(error,java.lang.Math.abs(base.positions[i]-rotated.positions[i]));
            }
            assert error<.0001 : "Surface orientation changes leg mechanics: "+error;
        }
        var feed=new GlbPose(model);
        feed.sampleFeeding(0,1);var first=feed.positions.clone();double change=0;
        for(int frame=1;frame<240;frame++) {
            feed.sampleFeeding(frame/120.0,1);
            for(int i=0;i<first.length;i++) {
                assert Float.isFinite(feed.positions[i]);
                change=java.lang.Math.max(change,java.lang.Math.abs(first[i]-feed.positions[i]));
            }
        }
        assert change>.0001 && change<.15 : "Feeding should articulate, not shake the whole creature: "+change;
        for(var normal:new Vector3d[]{new Vector3d(0,1,0),new Vector3d(1,0,0),new Vector3d(0,-1,0)}) {
            var rotation=SurfaceFrame.rotation(normal,new Vector3d(0,0,1));
            assert rotation.transform(new Vector3d(0,1,0)).distance(normal)<1e-7;
        }
        System.out.println("SURFACE PASS: floor/wall/ceiling contact equivariance");
    }
}
