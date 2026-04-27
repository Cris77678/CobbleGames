package com.cobblegames.minigame;

import com.cobblegames.arena.Arena;
import com.cobblegames.arena.ArenaManager;
import com.cobblegames.core.CobbleGames;
import com.cobblegames.minigame.impl.*;
import com.cobblegames.minigame.impl.race.RaceGame;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class GameManager {

    private final ArenaManager arenaManager;
    private final Map<String, Minigame> activeGames = new ConcurrentHashMap<>();
    // BUG #6 FIX: snapshot de UUIDs guardado ANTES de que returnPlayersToLobby los borre de players
    private final Map<UUID, String> playerGameMap = new ConcurrentHashMap<>();
    private final Map<String, Function<Arena, Minigame>> gameRegistry = new LinkedHashMap<>();

    public GameManager(ArenaManager arenaManager) {
        this.arenaManager = arenaManager;
        registerDefaultGames();
    }

    private void registerDefaultGames() {
        gameRegistry.put("race_ground",    arena -> new RaceGame(arena, RaceGame.RaceType.GROUND));
        gameRegistry.put("race_flying",    arena -> new RaceGame(arena, RaceGame.RaceType.FLYING));
        gameRegistry.put("hot_potato",     HotPotatoGame::new);
        gameRegistry.put("king_of_hill",   KingOfHillGame::new);
        gameRegistry.put("coin_collector", CoinCollectorGame::new);
        gameRegistry.put("freeze_tag",     FreezeTagGame::new);
        gameRegistry.put("red_light",      RedLightGame::new);
    }

    public void registerGame(String id, Function<Arena, Minigame> factory) {
        gameRegistry.put(id, factory);
        CobbleGames.LOGGER.info("[CobbleGames] Minijuego externo registrado: {}", id);
    }

    public Minigame createGame(String gameId, String arenaId) {
        Arena arena = arenaManager.getArena(arenaId);
        if (arena == null) return null;
        Function<Arena, Minigame> factory = gameRegistry.get(gameId);
        if (factory == null) return null;
        String instanceId = arenaId + ":" + gameId;
        if (activeGames.containsKey(instanceId)) return null;
        Minigame game = factory.apply(arena);
        activeGames.put(instanceId, game);
        return game;
    }

    public String joinGame(ServerPlayerEntity player, String instanceId) {
        if (playerGameMap.containsKey(player.getUuid())) return "§cYa estás en una partida.";
        Minigame game = activeGames.get(instanceId);
        if (game == null) return "§cNo existe esa partida.";
        if (game.onPlayerJoin(player)) {
            playerGameMap.put(player.getUuid(), instanceId);
            return "§aUnido a §e" + game.getDisplayName();
        }
        return "§cNo se pudo unir a la partida.";
    }

    public String leaveGame(ServerPlayerEntity player) {
        String instanceId = playerGameMap.remove(player.getUuid());
        if (instanceId == null) return "§cNo estás en ninguna partida.";
        Minigame game = activeGames.get(instanceId);
        if (game != null) game.onPlayerLeave(player);
        return "§7Saliste de §e" + (game != null ? game.getDisplayName() : "la partida") + "§7.";
    }

    public boolean forceStart(String instanceId, MinecraftServer server) {
        Minigame game = activeGames.get(instanceId);
        if (game == null || game.getState() != GameState.WAITING) return false;
        game.onStart(server);
        return true;
    }

    public void tick(MinecraftServer server) {
        Iterator<Map.Entry<String, Minigame>> it = activeGames.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Minigame> entry = it.next();
            Minigame game = entry.getValue();
            game.onTick(server);

            // BUG #10 FIX: usar interfaz Minigame en lugar de cast forzado a AbstractMinigame
            boolean ended = (game instanceof AbstractMinigame am)
                ? am.isFullyEnded()
                : game.getState() == GameState.ENDING;

            if (ended) {
                // BUG #6 FIX: limpiar TODOS los UUIDs que apunten a esta instancia,
                // incluyendo los de jugadores que se desconectaron durante ENDING
                String instanceId = entry.getKey();
                playerGameMap.entrySet().removeIf(e -> instanceId.equals(e.getValue()));
                it.remove();
                CobbleGames.LOGGER.info("[CobbleGames] Partida finalizada y removida: {}", instanceId);
            }
        }
    }

    public Minigame getPlayerGame(ServerPlayerEntity player) {
        String id = playerGameMap.get(player.getUuid());
        return id != null ? activeGames.get(id) : null;
    }

    /** Notifica al GameManager que un jugador se desconectó para limpiar su entrada. */
    public void onPlayerDisconnect(UUID uuid) {
        playerGameMap.remove(uuid);
    }

    public Minigame getGame(String instanceId) { return activeGames.get(instanceId); }
    public Map<String, Minigame> getActiveGames() { return Collections.unmodifiableMap(activeGames); }
    public Set<String> getRegisteredGameIds() { return Collections.unmodifiableSet(gameRegistry.keySet()); }

    public void shutdownAll() {
        MinecraftServer server = CobbleGames.getServer();
        activeGames.values().forEach(g -> g.onEnd(server, EndReason.ADMIN_STOP));
        activeGames.values().forEach(g -> {
            if (g instanceof AbstractMinigame am) am.returnPlayersToLobby(server);
        });
        activeGames.clear();
        playerGameMap.clear();
    }
}
