package dev.flybrain.render;

import dev.flybrain.FlyBrain;
import dev.flybrain.model.GlbModel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class FlyBrainClient implements ClientModInitializer {
    static final Identifier MODEL = Identifier.of("flybrain", "models/creature-animated.glb");
    static final Identifier TEXTURE = Identifier.of("flybrain", "dynamic/creature");
    static GlbModel model;
    @Override public void onInitializeClient() {
        dev.flybrain.voice.VoiceClient.initialize();
        CreatureAudio.initialize();
        EntityRendererRegistry.register(FlyBrain.CREATURE, CreatureRenderer::new);
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("flybrain", "creature_model"); }
            @Override public void reload(ResourceManager manager) {
                try (var stream = manager.getResourceOrThrow(MODEL).getInputStream()) {
                    var loaded = new GlbModel(stream.readAllBytes());
                    // Embedded JPEG is decoded, not replaced or repainted.
                    var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(loaded.baseColor));
                    if (image == null) throw new IOException("Embedded base-color image failed to decode");
                    var nativeImage = new NativeImage(image.getWidth(), image.getHeight(), false);
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getRGB(x, y);
                        nativeImage.setColor(x, y, (argb & 0xff00ff00) | ((argb >> 16) & 255) | ((argb & 255) << 16));
                    }
                    var texture = new NativeImageBackedTexture(nativeImage);
                    texture.setFilter(true, false);
                    MinecraftClient.getInstance().getTextureManager().registerTexture(TEXTURE, texture);
                    model = loaded;
                    org.slf4j.LoggerFactory.getLogger("FlyBrain").info("Loaded supplied GLB: {} vertices, {} triangles, {} joints, {} animations; bounds {} -> {}",
                            loaded.positions.length / 3, loaded.indices.length / 3, loaded.jointCount, loaded.animationCount, loaded.min, loaded.max);
                } catch (IOException e) { throw new IllegalStateException("Supplied creature model failed to load", e); }
            }
        });
    }
}
