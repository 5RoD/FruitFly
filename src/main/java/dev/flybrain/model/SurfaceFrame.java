package dev.flybrain.model;

import org.joml.*;

/** A right-handed frame with local +Y pointing away from the supporting surface. */
public final class SurfaceFrame {
    private SurfaceFrame(){}
    public static Quaterniond rotation(Vector3d normal,Vector3d heading) {
        if(!normal.isFinite() || !heading.isFinite() || normal.lengthSquared()<1e-12)
            throw new IllegalArgumentException("Invalid surface frame");
        var up=new Vector3d(normal).normalize();
        var forward=new Vector3d(heading).fma(-heading.dot(up),up);
        if(forward.lengthSquared()<1e-8) {
            forward.set(java.lang.Math.abs(up.z)<.9?new Vector3d(0,0,1):new Vector3d(1,0,0));
            forward.fma(-forward.dot(up),up);
        }
        forward.normalize();var right=new Vector3d(up).cross(forward).normalize();
        return new Matrix3d().setColumn(0,right).setColumn(1,up).setColumn(2,forward).getNormalizedRotation(new Quaterniond());
    }
}
