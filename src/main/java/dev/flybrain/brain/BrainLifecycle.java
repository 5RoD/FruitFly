package dev.flybrain.brain;

import dev.flybrain.FlyBrain;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static net.minecraft.server.command.CommandManager.literal;

/** Local, trusted server configuration only. Process work never runs on a game tick. */
public final class BrainLifecycle {
    private static volatile ManagedBrainProcess process;
    private static ScheduledExecutorService worker;
    private BrainLifecycle(){}
    public static String status(){var p=process;return p==null?"STOPPED":p.status();}
    public static void initialize(){
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            var config=FlyBrain.config;Path game=FabricLoader.getInstance().getGameDir().toAbsolutePath();
            process=new ManagedBrainProcess(List.of(game.resolve(config.brainPython).normalize().toString(),"-u",
                    game.resolve(config.brainScript).normalize().toString(),"--port",Integer.toString(config.externalPort)),
                    game.resolve("logs/flybrain-process.log"),config.externalPort,config.brainAutoStart);
            worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"flybrain-process");t.setDaemon(true);return t;});
            worker.scheduleWithFixedDelay(()->{
                try {var p=process;if(p!=null)p.poll();}
                catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("FlyBrain").error("Brain supervisor error",error);}
            },0,1,TimeUnit.SECONDS);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            if(worker!=null)worker.shutdownNow();var p=process;if(p!=null)p.close();process=null;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(literal("flybrain")
                .then(literal("brain").requires(s->s.hasPermissionLevel(2))
                    .then(literal("status").executes(c->{c.getSource().sendFeedback(()->Text.literal(status()),false);return 1;}))
                    .then(literal("restart").executes(c->{
                        var p=process;var server=c.getSource().getServer();var source=c.getSource();
                        if(p==null || worker==null){source.sendError(Text.literal("Brain supervisor not started"));return 0;}
                        worker.execute(()->{boolean restarted=p.restart();server.execute(()->source.sendFeedback(()->Text.literal(restarted?
                                "Managed brain restart requested":"External brain is not owned; it has not been terminated"),false));});return 1;
                    })))));
    }
}
