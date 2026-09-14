package dev.flybrain.brain;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.List;

/** Poll only on its background worker. Never adopts ownership of an existing process. */
public final class ManagedBrainProcess implements AutoCloseable {
    private final List<String> command;
    private final Path log;
    private final int port;
    private final boolean enabled;
    private volatile Process owned;
    private int attempts,misses;
    private long retryAt,launchedAt;
    private volatile boolean closed,restartRequested;
    private volatile HttpURLConnection activeRequest;
    private volatile String status="STARTING";

    public ManagedBrainProcess(List<String> command,Path log,int port,boolean enabled) {
        if(command.isEmpty() || command.stream().anyMatch(s->s==null || s.isBlank()) || port<1024 || port>65535)
            throw new IllegalArgumentException("Invalid managed brain command/port");
        this.command=List.copyOf(command);this.log=log.toAbsolutePath();this.port=port;this.enabled=enabled;
    }
    public String status(){return status;}
    static int retrySeconds(int failures){return new int[]{1,2,5,10,30}[Math.clamp(failures,0,4)];}
    static boolean recognized(String json) {
        try {
            var body=JsonParser.parseString(json).getAsJsonObject();
            return body.get("protocol").getAsInt()==1 && body.get("ready").getAsBoolean()
                    && body.get("model").getAsString().equals("shiu-brian2-fafb783");
        } catch(RuntimeException malformed){return false;}
    }
    public void poll() {
        if(closed)return;
        if(restartRequested){restartRequested=false;var child=owned;if(child!=null&&child.isAlive())child.destroy();retryAt=0;attempts=0;launchedAt=0;misses=3;}
        long now=System.nanoTime();
        if(now<retryAt)return;
        HttpURLConnection http=null;boolean listening=false;
        try {
            http=(HttpURLConnection)URI.create("http://127.0.0.1:"+port+"/health").toURL().openConnection();
            activeRequest=http;
            var request=http;
            java.util.concurrent.CompletableFuture.delayedExecutor(2,java.util.concurrent.TimeUnit.SECONDS).execute(()->{
                if(activeRequest==request)request.disconnect();
            });
            http.setConnectTimeout(500);http.setReadTimeout(1000);http.setInstanceFollowRedirects(false);
            int code=http.getResponseCode();listening=true;
            if(code==200)try(var in=http.getInputStream()) {
                byte[] bytes=in.readNBytes(4097);
                if(bytes.length<=4096 && recognized(new String(bytes,java.nio.charset.StandardCharsets.UTF_8))) {
                    status="READY ("+(owned!=null && owned.isAlive()?"managed":"external")+")";
                    attempts=misses=0;launchedAt=now-60_000_000_000L;retryAt=now+5_000_000_000L;return;
                }
            }
        } catch(IOException error) {
            // A timeout is not proof that the port is free: never start a competing service.
            try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),200);listening=true;}
            catch(IOException absent){listening=false;}
        } finally {activeRequest=null;if(http!=null)http.disconnect();}
        if(closed)return;
        if(owned!=null && owned.isAlive()) {
            if(now-launchedAt<60_000_000_000L || ++misses<3){status="WARMING UP / RETRYING";return;}
            owned.destroy();status="RESTARTING unresponsive managed brain";
            // Never overlap replacement with a child that has not actually exited.
            if(owned.isAlive())return;
        }
        if(listening){status="EXTERNAL PORT OCCUPIED (health unavailable; not terminated)";return;}
        if(!enabled){status="OFFLINE (auto-start disabled)";return;}
        retryAt=now+retrySeconds(attempts++)*1_000_000_000L;
        try {
            Files.createDirectories(log.getParent());
            if(Files.exists(log) && Files.size(log)>8_000_000)Files.move(log,log.resolveSibling(log.getFileName()+".previous"),StandardCopyOption.REPLACE_EXISTING);
            var child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            owned=child;if(closed){child.destroy();return;}
            launchedAt=now;misses=0;status="STARTING managed brain";
        } catch(IOException error){status="RETRY: "+error.getMessage();}
    }
    public boolean restart() {
        if(closed || owned==null)return false;
        restartRequested=true;status="RESTART REQUESTED";return true;
    }
    @Override public void close() {
        closed=true;var request=activeRequest;
        if(request!=null)java.util.concurrent.CompletableFuture.runAsync(request::disconnect);
        var child=owned;if(child!=null && child.isAlive())child.destroy();status="STOPPED";
    }
}
