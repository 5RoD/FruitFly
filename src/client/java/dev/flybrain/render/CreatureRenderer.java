package dev.flybrain.render;

import dev.flybrain.FlyBrain;
import dev.flybrain.entity.CreatureEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** Skeletal playback only; actual movement remains owned by the entity/neural layers. */
public final class CreatureRenderer extends EntityRenderer<CreatureEntity> {
    private final java.util.WeakHashMap<CreatureEntity,CreatureAnimation> animations=new java.util.WeakHashMap<>();
    private dev.flybrain.model.GlbModel loadedModel;
    public CreatureRenderer(EntityRendererFactory.Context context) { super(context); shadowRadius = .6f; }
    @Override public Identifier getTexture(CreatureEntity entity) { return FlyBrainClient.TEXTURE; }
    @Override public void render(CreatureEntity entity, float yaw, float tickDelta, MatrixStack matrices,
            VertexConsumerProvider consumers, int light) {
        var model = FlyBrainClient.model;
        if (model == null) return;
        if(loadedModel!=model) { animations.clear();loadedModel=model; }
        float scale = (float)FlyBrain.config.modelScale*entity.growthScale();
        var position=entity.getLerpedPos(tickDelta);
        var animation=animations.computeIfAbsent(entity,e->new CreatureAnimation(model));
        var orientation=animation.orientation(entity,yaw,tickDelta);
        double halfHeight=(model.max.y-model.min.y)*scale*.5;
        var modelToWorld=new org.joml.Matrix4d().translation(position.x,position.y+halfHeight,position.z)
                .rotate(orientation).scale(scale)
                .translate(-(model.min.x+model.max.x)/2,-(model.min.y+model.max.y)/2,-(model.min.z+model.max.z)/2);
        var pose=animation.update(entity,tickDelta,modelToWorld);
        matrices.push();
        matrices.translate(0,halfHeight,0);
        matrices.multiply(new org.joml.Quaternionf(orientation));
        matrices.scale(scale, scale, scale);
        matrices.translate(-(model.min.x + model.max.x) / 2, -(model.min.y+model.max.y)/2, -(model.min.z + model.max.z) / 2);
        var vertex = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(entity)));
        var entry = matrices.peek();
        // Vanilla entity layers use quads: repeat each triangle's final vertex, without changing its surface.
        for (int triangle = 0; triangle < model.indices.length; triangle += 3) {
            for (int corner = 0; corner < 4; corner++) {
                int index = model.indices[triangle + Math.min(corner, 2)], p = index * 3, uv = index * 2;
                double blood=Math.clamp((entity.growthScale()-1)/.25,0,1);
                double patch=Math.sin(model.positions[p]*23+Math.sin(model.positions[p+2]*17))*Math.cos(model.positions[p+1]*19);
                float stain=pose.wingVertices[index]?0:(float)(blood*Math.clamp((patch+.45)*1.8,0,1));
                vertex.vertex(entry.getPositionMatrix(), pose.positions[p], pose.positions[p + 1], pose.positions[p + 2])
                        .color((int)(255-95*stain), (int)(255-215*stain), (int)(255-215*stain), 255).texture(model.uvs[uv], model.uvs[uv + 1])
                        .overlay(OverlayTexture.DEFAULT_UV).light(light)
                        .normal(entry, pose.normals[p], pose.normals[p + 1], pose.normals[p + 2]);
            }
        }
        if(animation.wingAmount()>.02f) {
            var exposure=consumers.getBuffer(RenderLayer.getEntityTranslucent(getTexture(entity)));
            for(var positions:pose.wingExposure) for(int triangle=0;triangle<model.indices.length;triangle+=3) {
                if(!pose.wingVertices[model.indices[triangle]] || !pose.wingVertices[model.indices[triangle+1]] || !pose.wingVertices[model.indices[triangle+2]]) continue;
                for(int corner=0;corner<4;corner++) {
                    int index=model.indices[triangle+Math.min(corner,2)],p=index*3,uv=index*2;
                    exposure.vertex(entry.getPositionMatrix(),positions[p],positions[p+1],positions[p+2])
                            .color(255,255,255,(int)(65*animation.wingAmount())).texture(model.uvs[uv],model.uvs[uv+1])
                            .overlay(OverlayTexture.DEFAULT_UV).light(light)
                            .normal(entry,pose.normals[p],pose.normals[p+1],pose.normals[p+2]);
                }
            }
        }
        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, consumers, light);
    }
}
