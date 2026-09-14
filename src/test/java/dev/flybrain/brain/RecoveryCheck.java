package dev.flybrain.brain;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

/** No real brain is killed or replaced by these isolated lifecycle checks. */
public final class RecoveryCheck {
    public static void main(String[] args) throws Exception {
        String ready="{\"protocol\":1,\"model\":\"shiu-brian2-fafb783\",\"ready\":true}";
        if(args.length==2 && args[0].equals("--child")) {
            var child=HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[1])),0);
            child.createContext("/health",e->{var b=ready.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);try(var out=e.getResponseBody()){out.write(b);}});
            child.start();Thread.sleep(60_000);child.stop(0);return;
        }
        assert ManagedBrainProcess.recognized(ready);
        for(String bad:List.of("{}","null","garbage",ready.replace("true","false"),ready.replace("fafb783","fake")))
            assert !ManagedBrainProcess.recognized(bad) : bad;
        int[] delays={1,2,5,10,30,30,30};
        for(int i=0;i<delays.length;i++)assert ManagedBrainProcess.retrySeconds(i)==delays[i];
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/health", exchange->{
            byte[] bytes=ready.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var stream=exchange.getResponseBody()){stream.write(bytes);}
        });server.start();
        try(var process=new ManagedBrainProcess(List.of("nonexistent-test-executable"),Path.of("build/recovery-test.log"),server.getAddress().getPort(),true)) {
            process.poll();assert process.status().equals("READY (external)") : process.status();
            assert !process.restart() : "Must not kill an external service";
            process.poll();assert process.status().equals("READY (external)");
        } finally {server.stop(0);}
        try(var process=new ManagedBrainProcess(List.of("nonexistent-test-executable"),Path.of("build/recovery-test.log"),server.getAddress().getPort(),false)) {
            process.poll();assert process.status().equals("OFFLINE (auto-start disabled)");
        }
        int freePort;try(var socket=new java.net.ServerSocket(0)){freePort=socket.getLocalPort();}
        var javaBin=Path.of(System.getProperty("java.home"),"bin");
        var command=List.of(javaBin.resolve(java.nio.file.Files.exists(javaBin.resolve("java.exe"))?"java.exe":"java").toString(),"-cp",System.getProperty("java.class.path"),RecoveryCheck.class.getName(),"--child",Integer.toString(freePort));
        try(var process=new ManagedBrainProcess(command,Path.of("build/recovery-child.log"),freePort,true)) {
            process.poll();assert process.status().equals("STARTING managed brain"):process.status();
            long deadline=System.nanoTime()+10_000_000_000L;
            while(!process.status().equals("READY (managed)")&&System.nanoTime()<deadline){Thread.sleep(100);process.poll();}
            assert process.status().equals("READY (managed)"):process.status();
            assert process.restart();process.poll();
        }
        try(var process=new ManagedBrainProcess(List.of("nonexistent-test-executable"),Path.of("build/recovery-test.log"),freePort,true)) {
            Thread.sleep(200);process.poll();assert process.status().startsWith("RETRY:"):process.status();
        }
        System.out.println("RECOVERY PASS identity, bounded backoff, external ownership and disabled launch");
    }
}
