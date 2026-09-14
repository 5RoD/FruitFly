package dev.flybrain.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.flybrain.FlyBrain;
import dev.flybrain.entity.CreatureEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.scoreboard.*;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import java.util.*;
import static net.minecraft.server.command.CommandManager.*;

/** Per-viewer packets only: never edits the server's scoreboard, creature or neural state. */
public final class DebugSidebar {
    private static final Map<UUID, Session> WATCHERS = new HashMap<>();
    private static final class Session {
        final UUID entity;
        final RegistryKey<World> dimension;
        final ScoreboardObjective objective;
        final Set<ScoreboardDisplaySlot> replaced = EnumSet.noneOf(ScoreboardDisplaySlot.class);
        String page;
        List<String> rows = List.of();
        Session(CreatureEntity mob, String page, ScoreboardObjective objective) {
            entity = mob.getUuid(); dimension = mob.getWorld().getRegistryKey();
            this.page = page; this.objective = objective;
        }
        CreatureEntity resolve(MinecraftServer server) {
            var world = server.getWorld(dimension);
            var found = world == null ? null : world.getEntity(entity);
            return found instanceof CreatureEntity mob ? mob : null;
        }
    }
    private DebugSidebar() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 5 != 0) return; // Four refreshes per game-second, no brain polling.
            for (UUID id : List.copyOf(WATCHERS.keySet())) {
                var player = server.getPlayerManager().getPlayer(id);
                if (player == null) WATCHERS.remove(id);
                else if (!FlyBrain.config.debug || !player.getCommandSource().hasPermissionLevel(2)) stop(player);
                else update(player, WATCHERS.get(id));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> WATCHERS.remove(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> WATCHERS.clear());
    }

    public static LiteralArgumentBuilder<ServerCommandSource> commands() {
        var command = literal("scoreboard").executes(c -> show(c.getSource(), "overview", null))
            .then(literal("on").executes(c -> show(c.getSource(), "overview", null)))
            .then(literal("off").executes(c -> {
                stop(c.getSource().getPlayerOrThrow());
                c.getSource().sendFeedback(() -> Text.literal("FlyBrain sidebar off; server sidebar restored."),false);
                return 1;
            }));
        for (String page : DebugLines.PAGES) command.then(literal(page).executes(c -> show(c.getSource(), page, null)));
        return command;
    }

    public static LiteralArgumentBuilder<ServerCommandSource> watchCommand() {
        return literal("watch").executes(c -> {
                var mob = FlyBrain.nearest(c.getSource());
                if (mob == null) { c.getSource().sendError(Text.literal("No loaded fly within64 blocks.")); return 0; }
                return show(c.getSource(), "overview", mob);
            })
            .then(argument("entity",EntityArgumentType.entity()).executes(c -> {
                var entity = EntityArgumentType.getEntity(c,"entity");
                if (!(entity instanceof CreatureEntity mob)) {
                    c.getSource().sendError(Text.literal("Select one flybrain:creature, not another entity type.")); return 0;
                }
                return show(c.getSource(), "overview", mob);
            }));
    }

    private static int show(ServerCommandSource source, String page, CreatureEntity explicit) throws CommandSyntaxException {
        var player = source.getPlayerOrThrow();
        if (!FlyBrain.config.debug) { source.sendError(Text.literal("Debug disabled in config/flybrain.json")); return 0; }
        var existing = WATCHERS.get(player.getUuid());
        if (explicit == null && existing != null) {
            existing.page = page;
            existing.objective.setDisplayName(title(page));
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(existing.objective,2));
            update(player,existing);
        } else {
            var mob = explicit == null ? FlyBrain.nearest(source) : explicit;
            if (mob == null) { source.sendError(Text.literal("No loaded fly within64 blocks. Use /flybrain spawn or /flybrain watch <UUID>.")); return 0; }
            stop(player);
            String name;
            do { name = "flydbg_" + UUID.randomUUID().toString().substring(0,8); }
            while (source.getServer().getScoreboard().getNullableObjective(name) != null);
            var objective = new Scoreboard().addObjective(name, ScoreboardCriterion.DUMMY, title(page),
                    ScoreboardCriterion.RenderType.INTEGER, false, BlankNumberFormat.INSTANCE);
            var session = new Session(mob,page,objective);
            WATCHERS.put(player.getUuid(),session);
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective,0));
            update(player,session);
        }
        var selected = WATCHERS.get(player.getUuid());
        source.sendFeedback(() -> Text.literal("Watching " + selected.entity + " | " + page
                + " | /flybrain scoreboard off | /flybrain debug for full details"),false);
        return 1;
    }

    private static Text title(String page) { return Text.literal("FlyBrain | " + page).formatted(Formatting.GOLD); }

    private static void update(ServerPlayerEntity player, Session session) {
        var snapshot = FlyBrain.snapshot(player.getServer(),session.resolve(player.getServer()));
        snapshot.put("trackedEntity",session.entity.toString());
        var rows = DebugLines.rows(session.page,snapshot);
        for (int i = 0; i < rows.size(); i++) {
            if (i < session.rows.size() && rows.get(i).equals(session.rows.get(i))) continue;
            var color = i == 0 ? (DebugLines.state(snapshot).equals("ONLINE") ? Formatting.GREEN : Formatting.YELLOW) : Formatting.WHITE;
            player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(session.objective.getName()+"_"+i,
                    session.objective.getName(),15-i,Optional.of(Text.literal(rows.get(i)).formatted(color)),Optional.of(BlankNumberFormat.INSTANCE)));
        }
        session.rows = rows;
        display(player,session,ScoreboardDisplaySlot.SIDEBAR);
        var team = player.getScoreboardTeam();
        var slot = team == null ? null : ScoreboardDisplaySlot.fromFormatting(team.getColor());
        if (slot != null) display(player,session,slot);
    }

    private static void display(ServerPlayerEntity player, Session session, ScoreboardDisplaySlot slot) {
        session.replaced.add(slot);
        player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(slot,session.objective));
    }

    private static void stop(ServerPlayerEntity player) {
        var session = WATCHERS.remove(player.getUuid());
        if (session == null) return;
        // ponytail: restores server-owned slots; other packet-only HUDs must coordinate sidebar ownership.
        for (var slot : session.replaced) player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(slot,
                player.getServer().getScoreboard().getObjectiveForSlot(slot)));
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(session.objective,1));
    }

    /** Full chat/export diagnostics follow the watched UUID, not a different nearest creature. */
    public static CreatureEntity selected(ServerCommandSource source) {
        var session = source.getEntity() instanceof ServerPlayerEntity player ? WATCHERS.get(player.getUuid()) : null;
        return session == null ? FlyBrain.nearest(source) : session.resolve(source.getServer());
    }
    public static CreatureEntity watchedOnly(ServerCommandSource source) {
        var session=source.getEntity() instanceof ServerPlayerEntity player?WATCHERS.get(player.getUuid()):null;
        return session==null?null:session.resolve(source.getServer());
    }

    public static int list(ServerCommandSource source) {
        var mobs = source.getWorld().getEntitiesByType(FlyBrain.CREATURE,e -> true).stream()
                .sorted(Comparator.comparingDouble(e -> e.squaredDistanceTo(source.getPosition()))).toList();
        source.sendFeedback(() -> Text.literal("Loaded flies in this dimension: " + mobs.size() + " (showing nearest20)"),false);
        mobs.stream().limit(20).forEach(mob -> source.sendFeedback(() -> Text.literal(mob.getUuidAsString() + " | "
                + DebugLines.state(FlyBrain.snapshot(source.getServer(),mob)) + " | "
                + String.format(Locale.ROOT,"%.1f blocks", Math.sqrt(mob.squaredDistanceTo(source.getPosition())))),false));
        return mobs.size();
    }

    public static int help(ServerCommandSource source) {
        for (String line : List.of("/flybrain spawn | list | debug | export",
                "/flybrain scene give | show | hide | list | select <id> | trigger | reset | stop | remove",
                "/flybrain brain status | restart; /flybrain creature reset",
                "/flybrain voice start [confirm] | stop | status | list | clear | delete <id> | share on/off | receive on/off",
                "/flybrain scoreboard on | off | overview | sensors | neurons | motion | performance | behavior",
                "/flybrain watch [UUID or single-entity selector] - lock to a fly",
                "Sidebar is personal, read-only, refreshes4 times/second. OP level2 required.",
                "Signals and firing rates explain activity; the dataset does not expose literal thoughts."))
            source.sendFeedback(() -> Text.literal(line),false);
        return 1;
    }
}
