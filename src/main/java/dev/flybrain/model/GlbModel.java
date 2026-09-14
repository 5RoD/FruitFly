package dev.flybrain.model;

import com.google.gson.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Reader for the supplied embedded, indexed, single-material GLB. Retains rig and skin data unchanged. */
public final class GlbModel {
    public final JsonObject document;
    public final float[] positions, normals, uvs, weights;
    public final int[] indices, jointIndices;
    public final Matrix4f[] restJoints;
    public final float[] restPositions, restNormals;
    public final byte[] baseColor;
    public final int jointCount, animationCount;
    public final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
    public final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
    private final ByteBuffer binary;

    public GlbModel(byte[] bytes) throws IOException {
        try {
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            require(bytes.length >= 28 && header.getInt() == 0x46546c67 && header.getInt() == 2
                    && header.getInt() == bytes.length, "Invalid GLB 2 header");
            int jsonSize = header.getInt();
            require(header.getInt() == 0x4e4f534a && jsonSize > 0 && jsonSize <= bytes.length - 28, "Invalid JSON chunk");
            byte[] json = new byte[jsonSize]; header.get(json);
            document = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            int binSize = header.getInt();
            require(header.getInt() == 0x004e4942 && binSize == header.remaining(), "Invalid BIN chunk");
            binary = header.slice().order(ByteOrder.LITTLE_ENDIAN);
            require(!document.has("extensionsRequired"), "Unsupported required glTF extension");
            require(array("meshes").size() == 1, "Expected supplied single mesh");
            JsonArray primitives = array("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives");
            require(primitives.size() == 1, "Expected supplied single primitive");
            JsonObject primitive = primitives.get(0).getAsJsonObject();
            require(integer(primitive, "mode", 4) == 4 && !primitive.has("targets"), "Expected triangle mesh without morph targets");
            JsonObject attributes = primitive.getAsJsonObject("attributes");
            positions = floats(attributes.get("POSITION").getAsInt(), "VEC3");
            normals = floats(attributes.get("NORMAL").getAsInt(), "VEC3");
            uvs = floats(attributes.get("TEXCOORD_0").getAsInt(), "VEC2");
            weights = floats(attributes.get("WEIGHTS_0").getAsInt(), "VEC4");
            jointIndices = integers(attributes.get("JOINTS_0").getAsInt(), "VEC4");
            indices = integers(primitive.get("indices").getAsInt(), "SCALAR");
            int vertices = positions.length / 3;
            require(normals.length == positions.length && uvs.length == vertices * 2 && weights.length == vertices * 4
                    && jointIndices.length == weights.length && indices.length % 3 == 0, "Mismatched geometry arrays");
            for (int index : indices) require(index >= 0 && index < vertices, "Index outside vertex buffer");
            require(array("skins").size() == 1, "Expected supplied single skin");
            JsonObject skin = array("skins").get(0).getAsJsonObject();
            JsonArray joints = skin.getAsJsonArray("joints");
            jointCount = joints.size();
            float[] inverse = floats(skin.get("inverseBindMatrices").getAsInt(), "MAT4");
            require(inverse.length == jointCount * 16, "Invalid inverse bind matrices");
            int nodeCount = array("nodes").size();
            Matrix4f[] world = new Matrix4f[nodeCount];
            boolean[] visiting = new boolean[nodeCount];
            int[] parents = new int[nodeCount]; java.util.Arrays.fill(parents, -1);
            for (int i = 0; i < nodeCount; i++) {
                var node = array("nodes").get(i).getAsJsonObject();
                if (node.has("children")) for (var child : node.getAsJsonArray("children")) {
                    int n = child.getAsInt(); require(n >= 0 && n < nodeCount && parents[n] == -1, "Invalid node tree"); parents[n] = i;
                }
            }
            restJoints = new Matrix4f[jointCount];
            for (int j = 0; j < jointCount; j++)
                restJoints[j] = new Matrix4f(world(joints.get(j).getAsInt(), parents, world, visiting))
                        .mul(new Matrix4f().set(java.util.Arrays.copyOfRange(inverse, j * 16, (j + 1) * 16)));
            // Joint world * inverse bind gives world-space skin positions; mesh transform is not applied twice.
            restPositions = new float[positions.length]; restNormals = new float[normals.length];
            for (int v = 0; v < vertices; v++) {
                float[] blended = new float[16], jointMatrix = new float[16];
                float total = 0;
                for (int c = 0; c < 4; c++) {
                    int offset = v * 4 + c, joint = jointIndices[offset]; float weight = weights[offset];
                    require(joint >= 0 && joint < jointCount && weight >= 0, "Invalid skin influence");
                    total += weight;
                    restJoints[joint].get(jointMatrix);
                    for (int k = 0; k < 16; k++) blended[k] += jointMatrix[k] * weight;
                }
                require(Math.abs(total - 1) < .001, "Skin weights must sum to one");
                Matrix4f blend = new Matrix4f().set(blended);
                Vector3f p = new Vector3f(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
                blend.transformPosition(p);
                restPositions[v * 3] = p.x; restPositions[v * 3 + 1] = p.y; restPositions[v * 3 + 2] = p.z;
                min.min(p); max.max(p);
                Vector3f n = new Vector3f(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]);
                new Matrix3f(blend).invert().transpose().transform(n).normalize();
                restNormals[v * 3] = n.x; restNormals[v * 3 + 1] = n.y; restNormals[v * 3 + 2] = n.z;
            }
            animationCount = document.has("animations") ? array("animations").size() : 0;
            JsonObject material = array("materials").get(primitive.get("material").getAsInt()).getAsJsonObject();
            int texture = material.getAsJsonObject("pbrMetallicRoughness").getAsJsonObject("baseColorTexture").get("index").getAsInt();
            int image = array("textures").get(texture).getAsJsonObject().get("source").getAsInt();
            var imageObject = array("images").get(image).getAsJsonObject();
            require(imageObject.has("bufferView"), "Only embedded images supported");
            var view = view(imageObject.get("bufferView").getAsInt());
            int offset = integer(view, "byteOffset", 0), length = view.get("byteLength").getAsInt();
            require(offset >= 0 && length > 0 && (long)offset + length <= binary.limit(), "Image outside buffer");
            baseColor = new byte[length]; binary.get(offset, baseColor);
        } catch (RuntimeException e) { throw new IOException("Unsupported or corrupt creature GLB: " + e.getMessage(), e); }
    }

    private Matrix4f world(int index, int[] parents, Matrix4f[] world, boolean[] visiting) {
        require(index >= 0 && index < world.length, "Invalid joint node");
        if (world[index] != null) return world[index];
        require(!visiting[index], "Cyclic node hierarchy"); visiting[index] = true;
        var node = array("nodes").get(index).getAsJsonObject();
        Matrix4f local = new Matrix4f();
        if (node.has("matrix")) local.set(numbers(node.getAsJsonArray("matrix"), 16));
        else {
            if (node.has("translation")) { float[] t = numbers(node.getAsJsonArray("translation"), 3); local.translate(t[0], t[1], t[2]); }
            if (node.has("rotation")) { float[] r = numbers(node.getAsJsonArray("rotation"), 4); local.rotate(new Quaternionf(r[0], r[1], r[2], r[3])); }
            if (node.has("scale")) { float[] s = numbers(node.getAsJsonArray("scale"), 3); local.scale(s[0], s[1], s[2]); }
        }
        world[index] = parents[index] < 0 ? local : new Matrix4f(world(parents[index], parents, world, visiting)).mul(local);
        visiting[index] = false; return world[index];
    }
    private float[] numbers(JsonArray a, int size) {
        require(a.size() == size, "Invalid transform size"); float[] data = new float[size];
        for (int i = 0; i < size; i++) { data[i] = a.get(i).getAsFloat(); require(Float.isFinite(data[i]), "Nonfinite transform"); }
        return data;
    }
    private JsonArray array(String key) { return document.getAsJsonArray(key); }
    private JsonObject view(int index) {
        JsonObject v = array("bufferViews").get(index).getAsJsonObject();
        require(v.get("buffer").getAsInt() == 0, "Only GLB buffer zero supported"); return v;
    }
    private double[] values(int index, String type, boolean floating) {
        var a = array("accessors").get(index).getAsJsonObject();
        require(type.equals(a.get("type").getAsString()) && !a.has("sparse")
                && (!a.has("normalized") || !a.get("normalized").getAsBoolean()), "Unsupported accessor");
        int component = a.get("componentType").getAsInt();
        require(floating ? component == 5126 : component == 5121 || component == 5123 || component == 5125, "Unsupported component type");
        int width = component == 5121 ? 1 : component == 5123 ? 2 : 4;
        int count = a.get("count").getAsInt();
        int channels = switch (type) { case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; case "MAT4" -> 16; default -> 1; };
        require(count > 0 && count <= 1_000_000, "Invalid accessor count");
        var v = view(a.get("bufferView").getAsInt());
        int viewOffset = integer(v, "byteOffset", 0), localOffset = integer(a, "byteOffset", 0);
        int stride = integer(v, "byteStride", channels * width), length = v.get("byteLength").getAsInt();
        require(viewOffset >= 0 && localOffset >= 0 && stride >= channels * width
                && (long)localOffset + (long)(count - 1) * stride + channels * width <= length
                && (long)viewOffset + length <= binary.limit(), "Accessor outside buffer");
        double[] result = new double[count * channels];
        for (int i = 0; i < count; i++) for (int c = 0; c < channels; c++) {
            int offset = viewOffset + localOffset + i * stride + c * width;
            double value = switch (component) {
                case 5126 -> binary.getFloat(offset); case 5121 -> Byte.toUnsignedInt(binary.get(offset));
                case 5123 -> Short.toUnsignedInt(binary.getShort(offset)); default -> Integer.toUnsignedLong(binary.getInt(offset));
            };
            require(Double.isFinite(value), "Nonfinite geometry"); result[i * channels + c] = value;
        }
        return result;
    }
    float[] floats(int index, String type) {
        double[] data = values(index, type, true); float[] result = new float[data.length];
        for (int i = 0; i < data.length; i++) result[i] = (float)data[i]; return result;
    }
    private int[] integers(int index, String type) {
        double[] data = values(index, type, false); int[] result = new int[data.length];
        for (int i = 0; i < data.length; i++) { require(data[i] <= Integer.MAX_VALUE, "Index overflow"); result[i] = (int)data[i]; } return result;
    }
    private static int integer(JsonObject object, String key, int fallback) { return object.has(key) ? object.get(key).getAsInt() : fallback; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
