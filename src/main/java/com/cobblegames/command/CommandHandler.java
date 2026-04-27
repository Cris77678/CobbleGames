package com.cobblegames.command;

import com.cobblegames.arena.Arena;
import com.cobblegames.arena.ArenaManager;
import com.cobblegames.core.CobbleGames;
import com.cobblegames.minigame.EndReason;
import com.cobblegames.minigame.GameManager;
import com.cobblegames.minigame.Minigame;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registro centralizado de comandos.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  /cg
 *    join <arenaId> <gameType>               Unirse a un juego
 *    leave                                   Salir del juego actual
 *    list                                    Listar partidas activas
 *    games                                   Listar tipos de juego
 *    start <instanceId>             [OP]     Forzar inicio
 *    stop  <instanceId>             [OP]     Forzar fin
 *
 *    arena
 *      create <id>                  [OP]     Crear arena
 *      list                                  Listar arenas
 *      info <arenaId>                        Info detallada
 *
 *      setlobby  <arenaId>          [OP]     Lobby en posición actual
 *      setworld  <arenaId> <worldId>[OP]     Cambiar mundo
 *
 *      addspawn    <arenaId>        [OP]     Añadir spawn
 *      clearspawns <arenaId>        [OP]     Borrar todos los spawns
 *
 *      setbounds <arenaId> <pos1|pos2>       [OP]  Definir límite min/max
 *        → pos1 guarda el primer punto en sesión; pos2 completa el cuboid
 *      clearbounds <arenaId>        [OP]     Borrar bounds
 *      showbounds  <arenaId>                 Ver bounds actuales
 *
 *      addcoin    <arenaId>         [OP]     Añadir spawn de moneda
 *      clearcoin  <arenaId>         [OP]     Borrar todos los coin spawns
 *
 *      addcheckpoint    <arenaId>   [OP]     Añadir checkpoint de vuelta (ordenado)
 *      removecheckpoint <arenaId> <#>[OP]    Borrar checkpoint por número
 *      clearcheckpoints <arenaId>   [OP]     Borrar todos los checkpoints
 *      listcheckpoints  <arenaId>            Listar checkpoints con posición
 *
 *      addboost    <arenaId>        [OP]     Añadir boost pad
 *      removeboost <arenaId> <#>    [OP]     Borrar boost por número
 *      clearboosts <arenaId>        [OP]     Borrar todos los boosts
 *      listboosts  <arenaId>                 Listar boosts con posición
 *
 *      sethill       <arenaId>      [OP]     Centro de colina
 *      sethillradius <arenaId> <r>  [OP]     Radio de colina
 *      setfinish     <arenaId>      [OP]     Línea de meta
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class CommandHandler {

    // BUG #9 FIX: mapa con timestamp para limpiar entradas expiradas (TTL 5 min)
    private static final java.util.Map<java.util.UUID, long[]> pendingBoundsPos1 = new java.util.HashMap<>();
    private static final long BOUNDS_TTL_MS = 5 * 60 * 1000L; // 5 minutos

    /** Guarda pos + timestamp. Limpia entradas expiradas en cada llamada. */
    private static void storePendingPos1(java.util.UUID uuid, BlockPos pos) {
        long now = System.currentTimeMillis();
        // Limpiar entradas antiguas
        pendingBoundsPos1.entrySet().removeIf(e -> now - e.getValue()[3] > BOUNDS_TTL_MS);
        pendingBoundsPos1.put(uuid, new long[]{ pos.getX(), pos.getY(), pos.getZ(), now });
    }

    /** Recupera y elimina el pos1 pendiente, o null si expiró/no existe. */
    private static BlockPos consumePendingPos1(java.util.UUID uuid) {
        long[] data = pendingBoundsPos1.remove(uuid);
        if (data == null) return null;
        if (System.currentTimeMillis() - data[3] > BOUNDS_TTL_MS) return null; // expirado
        return new BlockPos((int)data[0], (int)data[1], (int)data[2]);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("cg")
                    // ── Jugador ────────────────────────────────────────────────
                    .then(literal("join")
                        .then(argument("arenaId", StringArgumentType.word())
                        .then(argument("gameType", StringArgumentType.word())
                        .executes(ctx -> cmdJoin(ctx.getSource(),
                            StringArgumentType.getString(ctx, "arenaId"),
                            StringArgumentType.getString(ctx, "gameType"))))))

                    .then(literal("leave")
                        .executes(ctx -> cmdLeave(ctx.getSource())))

                    .then(literal("list")
                        .executes(ctx -> cmdList(ctx.getSource())))

                    .then(literal("games")
                        .executes(ctx -> cmdGames(ctx.getSource())))

                    // ── Admin ──────────────────────────────────────────────────
                    .then(literal("start")
                        .requires(s -> s.hasPermissionLevel(2))
                        .then(argument("instanceId", StringArgumentType.greedyString())
                        .executes(ctx -> cmdStart(ctx.getSource(),
                            StringArgumentType.getString(ctx, "instanceId")))))

                    .then(literal("stop")
                        .requires(s -> s.hasPermissionLevel(2))
                        .then(argument("instanceId", StringArgumentType.greedyString())
                        .executes(ctx -> cmdStop(ctx.getSource(),
                            StringArgumentType.getString(ctx, "instanceId")))))

                    // ── Arena ──────────────────────────────────────────────────
                    .then(literal("arena")

                        // ── Gestión básica ─────────────────────────────────
                        .then(literal("create")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("id", StringArgumentType.word())
                            .executes(ctx -> cmdArenaCreate(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))))

                        .then(literal("list")
                            .executes(ctx -> cmdArenaList(ctx.getSource())))

                        .then(literal("info")
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdArenaInfo(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("setlobby")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdSetLobby(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("setworld")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .then(argument("worldId", StringArgumentType.word())
                            .executes(ctx -> cmdSetWorld(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId"),
                                StringArgumentType.getString(ctx, "worldId"))))))

                        // ── Spawns ─────────────────────────────────────────
                        .then(literal("addspawn")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdAddSpawn(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("clearspawns")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdClearSpawns(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        // ── Bounds ─────────────────────────────────────────
                        .then(literal("setbounds")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .then(literal("pos1")
                            .executes(ctx -> cmdBoundsPos1(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId"))))
                            .then(literal("pos2")
                            .executes(ctx -> cmdBoundsPos2(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId"))))))

                        .then(literal("clearbounds")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdClearBounds(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("showbounds")
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdShowBounds(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        // ── Monedas ────────────────────────────────────────
                        .then(literal("addcoin")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdAddCoin(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("clearcoin")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdClearCoin(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        // ── Checkpoints de vuelta ──────────────────────────
                        .then(literal("addcheckpoint")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdAddCheckpoint(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("removecheckpoint")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .then(argument("number", IntegerArgumentType.integer(1))
                            .executes(ctx -> cmdRemoveCheckpoint(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId"),
                                IntegerArgumentType.getInteger(ctx, "number"))))))

                        .then(literal("clearcheckpoints")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdClearCheckpoints(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("listcheckpoints")
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdListCheckpoints(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        // ── Boost pads ─────────────────────────────────────
                        .then(literal("addboost")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdAddBoost(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("removeboost")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .then(argument("number", IntegerArgumentType.integer(1))
                            .executes(ctx -> cmdRemoveBoost(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId"),
                                IntegerArgumentType.getInteger(ctx, "number"))))))

                        .then(literal("clearboosts")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdClearBoosts(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("listboosts")
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdListBoosts(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        // ── King of the Hill ───────────────────────────────
                        .then(literal("sethill")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdSetHill(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))

                        .then(literal("sethillradius")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .then(argument("radius", DoubleArgumentType.doubleArg(1.0, 50.0))
                            .executes(ctx -> cmdSetHillRadius(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId"),
                                DoubleArgumentType.getDouble(ctx, "radius"))))))

                        // ── Meta ───────────────────────────────────────────
                        .then(literal("setfinish")
                            .requires(s -> s.hasPermissionLevel(2))
                            .then(argument("arenaId", StringArgumentType.word())
                            .executes(ctx -> cmdSetFinish(ctx.getSource(),
                                StringArgumentType.getString(ctx, "arenaId")))))
                    )
            )
        );
    }

    // ═══ Comandos de jugador ══════════════════════════════════════════════════

    private static int cmdJoin(ServerCommandSource src, String arenaId, String gameType) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) return 0;
        GameManager gm = CobbleGames.getInstance().getGameManager();
        ArenaManager am = CobbleGames.getInstance().getArenaManager();
        if (!am.exists(arenaId)) {
            player.sendMessage(Text.literal("§cArena '§e" + arenaId + "§c' no existe."), false); return 0;
        }
        if (!gm.getRegisteredGameIds().contains(gameType)) {
            player.sendMessage(Text.literal("§cTipo '§e" + gameType + "§c' inválido. Usa /cg games"), false); return 0;
        }
        String instanceId = arenaId + ":" + gameType;
        if (gm.getGame(instanceId) == null) {
            Minigame game = gm.createGame(gameType, arenaId);
            if (game == null) { player.sendMessage(Text.literal("§cNo se pudo crear la partida."), false); return 0; }
        }
        player.sendMessage(Text.literal(gm.joinGame(player, instanceId)), false);
        return 1;
    }

    private static int cmdLeave(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) return 0;
        src.sendMessage(Text.literal(CobbleGames.getInstance().getGameManager().leaveGame(player)));
        return 1;
    }

    private static int cmdList(ServerCommandSource src) {
        GameManager gm = CobbleGames.getInstance().getGameManager();
        if (gm.getActiveGames().isEmpty()) { src.sendMessage(Text.literal("§7No hay partidas activas.")); return 1; }
        src.sendMessage(Text.literal("§6=== Partidas activas ==="));
        gm.getActiveGames().forEach((id, g) ->
            src.sendMessage(Text.literal("§e" + id + " §7[" + g.getState() + "] §7(" + g.getPlayers().size() + " jug)")));
        return 1;
    }

    private static int cmdGames(ServerCommandSource src) {
        src.sendMessage(Text.literal("§6=== Tipos de juego ==="));
        CobbleGames.getInstance().getGameManager().getRegisteredGameIds()
            .forEach(id -> src.sendMessage(Text.literal("§e  " + id)));
        return 1;
    }

    private static int cmdStart(ServerCommandSource src, String instanceId) {
        boolean ok = CobbleGames.getInstance().getGameManager().forceStart(instanceId, src.getServer());
        src.sendMessage(Text.literal(ok ? "§aPartida iniciada." : "§cNo encontrada o ya en curso."));
        return ok ? 1 : 0;
    }

    private static int cmdStop(ServerCommandSource src, String instanceId) {
        Minigame g = CobbleGames.getInstance().getGameManager().getGame(instanceId);
        if (g == null) { src.sendMessage(Text.literal("§cNo existe esa partida.")); return 0; }
        g.onEnd(src.getServer(), EndReason.ADMIN_STOP);
        src.sendMessage(Text.literal("§aPartida detenida."));
        return 1;
    }

    // ═══ Arena — básico ═══════════════════════════════════════════════════════

    private static int cmdArenaCreate(ServerCommandSource src, String id) {
        ArenaManager am = CobbleGames.getInstance().getArenaManager();
        if (am.exists(id)) { src.sendMessage(Text.literal("§cYa existe esa arena.")); return 0; }
        Arena arena = new Arena(id);
        am.registerArena(arena);
        CobbleGames.getInstance().getConfigLoader().saveArena(arena);
        src.sendMessage(Text.literal("§aArena '§e" + id + "§a' creada."));
        return 1;
    }

    private static int cmdArenaList(ServerCommandSource src) {
        ArenaManager am = CobbleGames.getInstance().getArenaManager();
        src.sendMessage(Text.literal("§6=== Arenas (" + am.getArenaCount() + ") ==="));
        am.getAllArenas().forEach(a -> {
            String bounds = a.hasBounds()
                ? " §7bounds §a✔"
                : " §7bounds §c✘";
            src.sendMessage(Text.literal("§e" + a.getId()
                + " §7| spawns: §f" + a.getSpawnPoints().size()
                + " §7| checkpoints: §f" + a.getCheckpoints().size()
                + " §7| boosts: §f" + a.getBoostPositions().size()
                + bounds));
        });
        return 1;
    }

    private static int cmdArenaInfo(ServerCommandSource src, String arenaId) {
        Arena a = getArenaOrFail(src, arenaId);
        if (a == null) return 0;
        String boundsStr = a.hasBounds()
            ? posStr(a.getBoundsMin()) + " §7→ " + posStr(a.getBoundsMax())
              + " §7(§e" + a.boundsWidth() + "x" + a.boundsHeight() + "x" + a.boundsDepth() + " §7bloques)"
            : "§cNo definidos";
        src.sendMessage(Text.literal(
            "§6=== Arena: " + a.getId() + " ===\n"
            + "§7Nombre: §f" + a.getDisplayName() + "\n"
            + "§7Mundo:  §f" + a.getWorldId() + "\n"
            + "§7Lobby:  §f" + posStr(a.getLobbyPos()) + "\n"
            + "§7Spawns: §f" + a.getSpawnPoints().size() + "\n"
            + "§7Bounds: " + boundsStr + "\n"
            + "§7Checkpoints vuelta: §f" + a.getCheckpoints().size() + "\n"
            + "§7Boost pads: §f" + a.getBoostPositions().size() + "\n"
            + "§7Monedas: §f" + a.getCoinSpawnPoints().size() + "\n"
            + "§7Colina: §f" + posStr(a.getHillCenter()) + " r=" + a.getHillRadius() + "\n"
            + "§7Meta: §f" + posStr(a.getFinishLine())
        ));
        return 1;
    }

    private static int cmdSetLobby(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.setLobbyPos(player.getBlockPos());
            src.sendMessage(Text.literal("§aLobby definido en " + posStr(player.getBlockPos())));
            save(arena);
        });
    }

    private static int cmdSetWorld(ServerCommandSource src, String arenaId, String worldId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.setWorldId(worldId);
        save(arena);
        src.sendMessage(Text.literal("§aMundo de '§e" + arenaId + "§a' establecido a §e" + worldId));
        return 1;
    }

    // ═══ Spawns ═══════════════════════════════════════════════════════════════

    private static int cmdAddSpawn(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.addSpawnPoint(player.getBlockPos());
            src.sendMessage(Text.literal("§aSpawn §e#" + arena.getSpawnPoints().size()
                + " §aañadido en " + posStr(player.getBlockPos())));
            save(arena);
        });
    }

    private static int cmdClearSpawns(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.clearSpawnPoints();
        save(arena);
        src.sendMessage(Text.literal("§aTodos los spawns eliminados de §e" + arenaId));
        return 1;
    }

    // ═══ Bounds ═══════════════════════════════════════════════════════════════

    private static int cmdBoundsPos1(ServerCommandSource src, String arenaId) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { src.sendMessage(Text.literal("§cSolo jugadores.")); return 0; }
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;

        storePendingPos1(player.getUuid(), player.getBlockPos());
        src.sendMessage(Text.literal(
            "§aPosición 1 guardada: §e" + posStr(player.getBlockPos()) + "\n"
            + "§7Ahora ve a la esquina opuesta y ejecuta §e/cg arena setbounds " + arenaId + " pos2\n§8(Expira en 5 min)"));
        return 1;
    }

    private static int cmdBoundsPos2(ServerCommandSource src, String arenaId) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { src.sendMessage(Text.literal("§cSolo jugadores.")); return 0; }
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;

        BlockPos pos1 = consumePendingPos1(player.getUuid());
        if (pos1 == null) {
            src.sendMessage(Text.literal("§cPrimero define la pos1 con §e/cg arena setbounds " + arenaId + " pos1"));
            return 0;
        }
        BlockPos pos2 = player.getBlockPos();
        arena.setBounds(pos1, pos2);
        save(arena);
        src.sendMessage(Text.literal(
            "§aBounds definidos:\n"
            + "§7  Mín: §e" + posStr(arena.getBoundsMin()) + "\n"
            + "§7  Máx: §e" + posStr(arena.getBoundsMax()) + "\n"
            + "§7  Tamaño: §e" + arena.boundsWidth() + "x" + arena.boundsHeight() + "x" + arena.boundsDepth() + " §7bloques"));
        return 1;
    }

    private static int cmdClearBounds(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.clearBounds();
        save(arena);
        src.sendMessage(Text.literal("§aBounds eliminados de §e" + arenaId
            + "§a. Las monedas se generarán en toda la arena."));
        return 1;
    }

    private static int cmdShowBounds(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        if (!arena.hasBounds()) {
            src.sendMessage(Text.literal("§7Arena §e" + arenaId + " §7no tiene bounds definidos."));
        } else {
            src.sendMessage(Text.literal(
                "§6Bounds de §e" + arenaId + "§6:\n"
                + "§7  Mín: §e" + posStr(arena.getBoundsMin()) + "\n"
                + "§7  Máx: §e" + posStr(arena.getBoundsMax()) + "\n"
                + "§7  Tamaño: §a" + arena.boundsWidth() + "x" + arena.boundsHeight() + "x" + arena.boundsDepth() + " §7bloques"));
        }
        return 1;
    }

    // ═══ Monedas ═══════════════════════════════════════════════════════════════

    private static int cmdAddCoin(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.addCoinSpawnPoint(player.getBlockPos());
            src.sendMessage(Text.literal("§aMoneda §e#" + arena.getCoinSpawnPoints().size()
                + " §aañadida en " + posStr(player.getBlockPos())));
            save(arena);
        });
    }

    private static int cmdClearCoin(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.clearCoinSpawnPoints();
        save(arena);
        src.sendMessage(Text.literal("§aTodos los spawn de monedas eliminados de §e" + arenaId));
        return 1;
    }

    // ═══ Checkpoints de vuelta ════════════════════════════════════════════════

    private static int cmdAddCheckpoint(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.addCheckpoint(player.getBlockPos());
            int n = arena.getCheckpoints().size();
            src.sendMessage(Text.literal(
                "§aCheckpoint §e#" + n + " §aañadido en " + posStr(player.getBlockPos()) + "\n"
                + "§7Los jugadores deben pasar por §e#1 → #2 → … → #" + n + " §7para completar una vuelta."));
            save(arena);
        });
    }

    private static int cmdRemoveCheckpoint(ServerCommandSource src, String arenaId, int number) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        if (!arena.removeCheckpoint(number - 1)) {
            src.sendMessage(Text.literal("§cCheckpoint #" + number + " no existe. Total: " + arena.getCheckpoints().size()));
            return 0;
        }
        save(arena);
        src.sendMessage(Text.literal("§aCheckpoint #" + number + " eliminado. Quedan: §e" + arena.getCheckpoints().size()));
        return 1;
    }

    private static int cmdClearCheckpoints(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.clearCheckpoints();
        save(arena);
        src.sendMessage(Text.literal("§aTodos los checkpoints eliminados de §e" + arenaId));
        return 1;
    }

    private static int cmdListCheckpoints(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        var cps = arena.getCheckpoints();
        if (cps.isEmpty()) { src.sendMessage(Text.literal("§7Sin checkpoints en §e" + arenaId)); return 1; }
        src.sendMessage(Text.literal("§6Checkpoints de §e" + arenaId + " §6(" + cps.size() + "):"));
        for (int i = 0; i < cps.size(); i++) {
            String arrow = i == 0 ? "§aSTART→" : (i == cps.size() - 1 ? "§e→FINISH" : "§7→");
            src.sendMessage(Text.literal("  " + arrow + " §e#" + (i + 1) + " §7" + posStr(cps.get(i))));
        }
        return 1;
    }

    // ═══ Boost pads ═══════════════════════════════════════════════════════════

    private static int cmdAddBoost(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.addBoostPosition(player.getBlockPos());
            src.sendMessage(Text.literal(
                "§6⚡ Boost pad §e#" + arena.getBoostPositions().size()
                + " §aañadido en " + posStr(player.getBlockPos()) + "\n"
                + "§7Al pasar por aquí los jugadores recibirán velocidad extra."));
            save(arena);
        });
    }

    private static int cmdRemoveBoost(ServerCommandSource src, String arenaId, int number) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        if (!arena.removeBoostPosition(number - 1)) {
            src.sendMessage(Text.literal("§cBoost #" + number + " no existe. Total: " + arena.getBoostPositions().size()));
            return 0;
        }
        save(arena);
        src.sendMessage(Text.literal("§aBoost #" + number + " eliminado. Quedan: §e" + arena.getBoostPositions().size()));
        return 1;
    }

    private static int cmdClearBoosts(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.clearBoostPositions();
        save(arena);
        src.sendMessage(Text.literal("§aTodos los boost pads eliminados de §e" + arenaId));
        return 1;
    }

    private static int cmdListBoosts(ServerCommandSource src, String arenaId) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        var boosts = arena.getBoostPositions();
        if (boosts.isEmpty()) { src.sendMessage(Text.literal("§7Sin boost pads en §e" + arenaId)); return 1; }
        src.sendMessage(Text.literal("§6⚡ Boost pads de §e" + arenaId + " §6(" + boosts.size() + "):"));
        for (int i = 0; i < boosts.size(); i++) {
            src.sendMessage(Text.literal("  §e#" + (i + 1) + " §7" + posStr(boosts.get(i))));
        }
        return 1;
    }

    // ═══ KotH / Finish ════════════════════════════════════════════════════════

    private static int cmdSetHill(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.setHillCenter(player.getBlockPos());
            src.sendMessage(Text.literal("§aCentro de colina en " + posStr(player.getBlockPos())
                + " §7(radio actual: §e" + arena.getHillRadius() + "§7)"));
            save(arena);
        });
    }

    private static int cmdSetHillRadius(ServerCommandSource src, String arenaId, double radius) {
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        arena.setHillRadius(radius);
        save(arena);
        src.sendMessage(Text.literal("§aRadio de colina: §e" + radius + " §7bloques"));
        return 1;
    }

    private static int cmdSetFinish(ServerCommandSource src, String arenaId) {
        return withPlayerArena(src, arenaId, (player, arena) -> {
            arena.setFinishLine(player.getBlockPos());
            src.sendMessage(Text.literal("§aLínea de meta en " + posStr(player.getBlockPos())));
            save(arena);
        });
    }

    // ═══ Utilidades ═══════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface PlayerArenaAction {
        void execute(ServerPlayerEntity player, Arena arena);
    }

    private static int withPlayerArena(ServerCommandSource src, String arenaId, PlayerArenaAction action) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { src.sendMessage(Text.literal("§cSolo jugadores.")); return 0; }
        Arena arena = getArenaOrFail(src, arenaId);
        if (arena == null) return 0;
        action.execute(player, arena);
        return 1;
    }

    private static Arena getArenaOrFail(ServerCommandSource src, String arenaId) {
        Arena arena = CobbleGames.getInstance().getArenaManager().getArena(arenaId);
        if (arena == null) src.sendMessage(Text.literal("§cArena '§e" + arenaId + "§c' no encontrada."));
        return arena;
    }

    private static void save(Arena arena) {
        CobbleGames.getInstance().getConfigLoader().saveArena(arena);
    }

    private static String posStr(BlockPos pos) {
        return pos == null ? "§cno definida" : "§7(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "§7)";
    }
}
