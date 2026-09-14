package dev.flybrain;

import dev.flybrain.brain.*;
import dev.flybrain.config.BrainConfig;
import dev.flybrain.model.GlbModel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Run with gradlew brainCheck; plain assertions, no test framework. */
public final class PrototypeCheck {
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("--gait-model")) {
            gaitAssetCheck(new GlbModel(Files.readAllBytes(Path.of(args[1])))); return;
        }
        if (args.length == 1 && args[0].equals("--real")) { realSimulatorCheck(); return; }
        debugCheck();
        gaitCheck();
        byte[] source = Files.readAllBytes(Path.of("src/main/resources/assets/flybrain/models/creature.glb"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        assert hash.equals("5cfc131dc0ea9955a9a25ca45cca768bf7e244860fc21aa7f29c63f4272802d1") : hash;
        var model = new GlbModel(source);
        assert model.positions.length / 3 == 4578;
        assert model.indices.length / 3 == 3160;
        assert model.jointCount == 56 && model.animationCount == 0;
        assert Math.abs(model.min.y) < .001 && Math.abs(model.max.y - 1.7) < .001 : model.min + " -> " + model.max;
        double maxPoseDifference = 0;
        for (int i = 0; i < model.positions.length; i++) {
            assert Float.isFinite(model.restPositions[i]) && Float.isFinite(model.restNormals[i]);
            maxPoseDifference = Math.max(maxPoseDifference, Math.abs(model.restPositions[i] - model.positions[i]));
        }
        assert maxPoseDifference < .001 : "Bind pose changed " + maxPoseDifference;
        System.out.println("MODEL PASS sha256=" + hash + " vertices=4578 triangles=3160 joints=56 animations=0 restDelta=" + maxPoseDifference);
        var animated=new GlbModel(Files.readAllBytes(Path.of("src/main/resources/assets/flybrain/models/creature-animated.glb")));
        assert java.util.Arrays.equals(model.positions,animated.positions);
        var repaired=new GlbModel(Files.readAllBytes(Path.of("art/creature/creature-motion-rigged.glb")));
        assert java.util.Arrays.equals(repaired.weights,animated.weights) : "Unexpected motion-rig weight change";
        assert java.util.Arrays.equals(repaired.jointIndices,animated.jointIndices);
        assert java.util.Arrays.equals(model.normals,animated.normals);
        assert java.util.Arrays.equals(model.uvs,animated.uvs);
        assert java.util.Arrays.equals(model.indices,animated.indices);
        assert java.util.Arrays.equals(model.baseColor,animated.baseColor);
        assert model.document.get("nodes").equals(animated.document.get("nodes"));
        var pose=new dev.flybrain.model.GlbPose(animated);
        assert pose.clips().equals(java.util.Set.of("idle","walk","run","takeoff","air","land","strike","hurt","death","groom"));
        for(String clip:pose.clips()) {
            double displacement=0; float minY=Float.POSITIVE_INFINITY,maxY=-Float.MAX_VALUE;
            for(int frame=0;frame<=30;frame++) {
                pose.sample(clip,pose.duration(clip)*frame/30,1);
                for(int i=0;i<pose.positions.length;i++) {
                    assert Float.isFinite(pose.positions[i]) && Float.isFinite(pose.normals[i]);
                    displacement=Math.max(displacement,Math.abs(pose.positions[i]-model.restPositions[i]));
                    if(i%3==1) {minY=Math.min(minY,pose.positions[i]);maxY=Math.max(maxY,pose.positions[i]);}
                }
            }
            assert displacement>.0001 && displacement<1.8 : clip+" displacement="+displacement;
            assert minY>-.01 : clip+" penetrates floor: "+minY;
            System.out.println("ANIMATION "+clip+" displacement="+displacement+" boundsY="+minY+","+maxY);
        }
        for(String loop:java.util.List.of("idle","walk","run","air")) {
            pose.sample(loop,0,1);float[] first=pose.positions.clone();
            pose.sample(loop,pose.duration(loop),1);
            for(int i=0;i<first.length;i++) assert Math.abs(first[i]-pose.positions[i])<.00001 : "Loop seam "+loop;
            pose.sample(loop,pose.duration(loop)-.0001,1);
            for(int i=0;i<first.length;i++) assert Math.abs(first[i]-pose.positions[i])<(pose.wingVertices[i/3]?.008:.001) : "Approaching loop seam "+loop;
        }
        pose.sample("walk",.35,1);float[] beforeBlend=pose.positions.clone();pose.beginTransition();pose.sample("idle",.5,0);
        for(int i=0;i<beforeBlend.length;i++) assert Math.abs(beforeBlend[i]-pose.positions[i])<.00001 : "Transition snap";
        System.out.println("ANIMATION PASS 10 clips, original rig/mesh unchanged, finite poses, loop seams and blend continuity");
        MotionCheck.check(animated);
        gaitAssetCheck(animated);
        FootPlantCheck.check(animated);
        byte[] corrupt = source.clone(); corrupt[0] = 0;
        try { new GlbModel(corrupt); throw new AssertionError("Bad header accepted"); } catch (java.io.IOException expected) {}
        try { new BrainInput(1, Double.NaN, 0, 0, 0, 0, 0, true); throw new AssertionError("NaN accepted"); }
        catch (IllegalArgumentException expected) {}
        try { new BrainOutput(1, Double.POSITIVE_INFINITY, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1); throw new AssertionError("Infinity accepted"); }
        catch (IllegalArgumentException expected) {}

        BrainConfig config = new BrainConfig(); config.maxBrains = 1; config.timeoutMs = 200;
        assert config.simulationMode.equals("flywire-external");
        // This HTTP fixture verifies transport only. Biological checks use the real Python circuit.
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        config.externalPort = http.getAddress().getPort();
        var invalid = new java.util.concurrent.atomic.AtomicBoolean(false);
        http.createContext("/step", exchange -> {
            try {
                var json = com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var outputs = new com.google.gson.JsonObject();
                for (var entry : json.getAsJsonObject("samples").entrySet()) {
                    long seq = entry.getValue().getAsJsonObject().get("sequence").getAsLong();
                    var output = new BrainOutput(seq, .2, 0, 0, 20, 0, 0, 0, 0, 454, 17964, 1000);
                    outputs.add(entry.getKey(), new com.google.gson.Gson().toJsonTree(output));
                }
                String response = new com.google.gson.Gson().toJson(java.util.Map.of("protocol", 1,
                        "model", invalid.get() ? "synthetic" : "shiu-brian2-fafb783", "outputs", outputs));
                byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        http.start();
        UUID id = new UUID(1, 1);
        try (var service = new BrainService(config)) {
            assert service.submit(id, new BrainInput(1, 0, 0, 0, 0, 0, 0, true));
            assert !service.submit(new UUID(2, 2), new BrainInput(1, 0, 0, 0, 0, 0, 0, true));
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (service.result(id) == null && System.nanoTime() < deadline) {
                service.submit(id, new BrainInput(2, 0, 0, 0, 0, 0, 0, true)); Thread.sleep(10);
            }
            assert service.result(id) != null : "Worker did not produce output";
            assert service.diagnostics(id).registered() && service.diagnostics(id).latestSample() != null;
            assert service.diagnostics(id).lastResult() != null;
            invalid.set(true);
            deadline = System.nanoTime() + 2_000_000_000L;
            while (!service.failure.contains("not the required FlyWire model") && System.nanoTime() < deadline) {
                service.submit(id, new BrainInput(3, 0, 0, 0, 0, 0, 0, true)); Thread.sleep(10);
            }
            assert service.failure.contains("not the required FlyWire model") : "Model rejection was not exercised";
            assert service.result(id) == null : "Synthetic response accepted";
            invalid.set(false);
            deadline = System.nanoTime() + 2_000_000_000L;
            while (service.result(id) == null && System.nanoTime() < deadline) {
                service.submit(id, new BrainInput(4, 0, 0, 0, 0, 0, 0, true)); Thread.sleep(10);
            }
            assert service.result(id) != null : "Valid output did not recover";
            Thread.sleep(300);
            assert service.result(id) == null : "Stale command accepted";
            assert service.diagnostics(id).lastResult() != null : "Debug cannot explain expired output";
            service.remove(id); assert service.active() == 0;
            assert !service.diagnostics(id).registered() && service.diagnostics(id).lastResult() == null;
        } finally { http.stop(0); }
        try (var offline = new BrainService(config)) {
            offline.submit(id, new BrainInput(1, 1, 1, 1, 1, 1, 1, true));
            Thread.sleep(250);
            assert offline.result(id) == null : "Offline fallback generated movement";
        }
        assert !Files.exists(Path.of("src/main/java/dev/flybrain/brain/NeuralNetwork.java"));
        System.out.println("TRANSPORT PASS bounded async output; wrong model rejected; stale/offline output stops; no synthetic controller");
        System.out.println("PROTOTYPE CHECK PASS");
    }

    private static void gaitAssetCheck(GlbModel model) throws Exception {
        var legs=com.google.gson.JsonParser.parseString(Files.readString(Path.of("docs/gait-refinement/leg-map.json")))
                .getAsJsonObject().getAsJsonObject("legs");
        var pose=new dev.flybrain.model.GlbPose(model);
        for(String clip:java.util.List.of("walk","run")) {
            double distance=clip.equals("walk")?.32:.44, duty=clip.equals("walk")?.62:.56;
            double maxSlip=0, maxStanceDrift=0, maxPlantHeight=0, minLift=Double.POSITIVE_INFINITY;
            for(var entry:legs.entrySet()) {
                String name=entry.getKey(); var leg=entry.getValue().getAsJsonObject();
                double offset=name.endsWith("M")?.015:name.endsWith("H")?-.015:0;
                double phase=leg.get("phase").getAsDouble()+offset;
                var ids=leg.getAsJsonArray("contact_vertices");
                double[] previous=null, plantedStart=null; boolean previousStance=false; double peak=0;
                for(int frame=0;frame<=240;frame++) {
                    double u=frame/240.0;
                    pose.sample(clip,pose.duration(clip)*u,1);
                    double x=0,y=Double.POSITIVE_INFINITY,z=0;
                    for(var id:ids) {
                        int i=id.getAsInt()*3;
                        x+=pose.positions[i]; y=Math.min(y,pose.positions[i+1]); z+=pose.positions[i+2];
                    }
                    double[] point={x/ids.size(),y,z/ids.size()+distance*u};
                    double p=(u+phase)%1; if(p<0)p++;
                    boolean stance=p<duty;
                    if(stance) {
                        if(plantedStart==null) plantedStart=point;
                        maxStanceDrift=Math.max(maxStanceDrift,Math.hypot(point[0]-plantedStart[0],point[2]-plantedStart[2]));
                        maxPlantHeight=Math.max(maxPlantHeight,Math.abs(y-leg.getAsJsonArray("contact").get(1).getAsDouble()));
                        if(previousStance) maxSlip=Math.max(maxSlip,Math.hypot(point[0]-previous[0],point[2]-previous[2]));
                    } else plantedStart=null;
                    peak=Math.max(peak,y-leg.getAsJsonArray("contact").get(1).getAsDouble());
                    previous=point; previousStance=stance;
                }
                minLift=Math.min(minLift,peak);
                assert peak>.025 : clip+" "+name+" leg does not lift: "+peak;
            }
            assert maxPlantHeight<.001 : clip+" planted foot height "+maxPlantHeight;
            assert maxStanceDrift<.0015 : clip+" planted foot total drift "+maxStanceDrift;
            assert maxSlip<.0002 : clip+" planted foot slides per 1/240 cycle: "+maxSlip;
            System.out.println("SIX-LEG "+clip+" minLift="+minLift+" maxPlantHeight="+maxPlantHeight+" maxSampleSlip="+maxSlip+" maxStanceDrift="+maxStanceDrift);
        }
    }

    private static void gaitCheck() {
        for(int age:new int[]{0,2000000,Integer.MAX_VALUE-1}) {
            double before=dev.flybrain.model.GaitClock.seconds(age,0);
            for(int frame=1;frame<=6;frame++) {
                double now=dev.flybrain.model.GaitClock.seconds(age,frame/6f);
                assert Math.abs(now-before-1.0/120)<1e-7 : "Quantized sub-tick clock at age="+age;
                before=now;
            }
        }
        var gait = new dev.flybrain.model.GaitClock();
        gait.update(.02,.05,false);
        double end = gait.phase(1);
        assert end > 0 && gait.phase(0) == 0;
        assert Math.abs(gait.phase(.5)-end/2)<1e-10;
        gait.update(.04,.05,false);
        assert Math.abs(gait.phase(0)-end)<1e-10 : "Phase jumps at tick boundary";
        end=gait.phase(1);
        gait.update(0,.05,false);
        assert gait.phase(0)==end && gait.phase(1)==end : "Stationary feet keep sliding";
        gait.update(-.02,.05,false);
        assert gait.phase(1)<gait.phase(0) : "Backward gait plays forward";
        end=gait.phase(1);
        gait.update(10,.05,true);
        assert gait.phase(0)==end && gait.phase(1)==end && gait.speed()==0 : "Teleport advances gait";
        for(int i=0;i<15;i++) {
            end=gait.phase(1); gait.update(.15,.05,false);
            assert Math.abs(gait.phase(0)-end)<1e-10 : "Walk/run phase discontinuity";
        }
        assert gait.running();
        var jump = new dev.flybrain.movement.EscapeImpulse();
        for (int i=0;i<100;i++) assert jump.update(.46,true,.6)==0 : "Ordinary visual activity causes hops";
        assert jump.update(1,true,.6)==.42;
        assert jump.update(1,false,.6)==0;
        assert jump.update(1,true,.6)==0 : "Held escape signal causes repeated jumps";
        jump.update(.2,true,.6);
        assert jump.update(.8,true,.6)>0;
        jump.update(0,true,.6);
        assert jump.update(1,false,.6)==0 : "Midair thrust";
        assert jump.update(1,true,.6)==0 : "Old airborne burst jumps on landing";
        System.out.println("GAIT CHECK PASS continuous/reversible phase, stop/teleport, walk-run handoff, escape threshold and rearming");
    }

    private static void debugCheck() {
        var values = new java.util.LinkedHashMap<String,Object>();
        assert dev.flybrain.debug.DebugLines.state(values).equals("NO FLY");
        values.put("entity", "12345678-1234-1234-1234-123456789012");
        values.put("alive", true);
        assert dev.flybrain.debug.DebugLines.state(values).equals("STOPPED");
        values.put("serviceRunning", true); values.put("activeBrains", 1); values.put("maxBrains", 1);
        assert dev.flybrain.debug.DebugLines.state(values).equals("NO SLOT");
        values.put("registered", true);
        assert dev.flybrain.debug.DebugLines.state(values).equals("WAITING");
        values.put("lastFailure", "Connection refused\nsecond line\u00a7k");
        assert dev.flybrain.debug.DebugLines.state(values).equals("ERROR");
        values.put("hasResult", true); values.put("resultAgeMs", 1001.0); values.put("timeoutMs", 1000);
        assert dev.flybrain.debug.DebugLines.state(values).equals("STALE");
        values.put("resultAgeMs", 10.0);
        assert dev.flybrain.debug.DebugLines.state(values).equals("ONLINE");
        values.put("input", new BrainInput(8,.8,.2,1,0,.5,0,true));
        var output = new BrainOutput(7,.4,-.3,.2,40,0,20,10,5,918,32057,10000000);
        values.put("motorAndDrives", output); values.put("lastNeuralOutput", output);
        values.put("horizontalCollision", true);
        assert dev.flybrain.debug.DebugLines.reason(values).contains("collision");
        values.put("noAi", true);
        assert dev.flybrain.debug.DebugLines.reason(values).contains("NoAI");
        for (String page : dev.flybrain.debug.DebugLines.PAGES) {
            var rows = dev.flybrain.debug.DebugLines.rows(page, values);
            assert rows.size() == 15 : page + rows.size();
            assert rows.stream().allMatch(line -> line.length() <= 72 && !line.contains("\n") && !line.contains("\u00a7"));
        }
        assert dev.flybrain.debug.DebugLines.rows("sensors",values).stream().anyMatch(s -> s.contains("80%"));
        assert dev.flybrain.debug.DebugLines.rows("neurons",values).stream().anyMatch(s -> s.contains("40.0"));
        values.put("alive", false);
        assert dev.flybrain.debug.DebugLines.state(values).equals("DEAD");
        try { dev.flybrain.debug.DebugLines.rows("bad-page",values); throw new AssertionError("Unknown page accepted"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("DEBUG CHECK PASS states, reasons, five bounded pages, sensors, firing rates and text sanitization");
    }

    private static void realSimulatorCheck() throws Exception {
        var config = new BrainConfig();
        UUID id = UUID.randomUUID();
        long sequence = 0, lastObserved = -1, observed = 0;
        double maxLatency = 0;
        try (var service = new BrainService(config)) {
            long end = System.nanoTime() + 8_000_000_000L;
            while (System.nanoTime() < end) {
                service.submit(id, new BrainInput(++sequence, 1, 1, 0, 0, .5, 0, true));
                var result = service.result(id);
                if (result != null) {
                    assert result.output().neurons() == 918 && result.output().connections() == 32057;
                    maxLatency = Math.max(maxLatency, (result.completedNanos() - result.submittedNanos()) / 1e6);
                    if (result.output().forwardHz() > 0 && result.output().sequence() != lastObserved) observed++;
                    lastObserved = result.output().sequence();
                }
                Thread.sleep(50);
            }
            assert observed >= 20 : "Real simulator output absent/too slow: " + service.failure;
            assert maxLatency < config.timeoutMs : maxLatency;
            System.out.println("REAL FLYWIRE HTTP PASS distinctPositiveResponses=" + observed + " maxLatencyMs=" + maxLatency
                    + " messages=" + service.messages.get() + " brainHz=" + config.brainHz);
            service.remove(id);
            assert service.result(id) == null;
        }
    }
}



