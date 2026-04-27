package com.cobblegames.core;

import com.cobblegames.arena.ArenaManager;
import com.cobblegames.command.CommandHandler;
import com.cobblegames.config.ConfigLoader;
import com.cobblegames.minigame.GameManager;
import com.cobblegames.minigame.Minigame;
import com.cobblegames.music.MinigameSounds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CobbleGames implements ModInitializer {

    public static final String MOD_ID = "cobblegames";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CobbleGames instance;
    private static MinecraftServer server;
    private GameManager gameManager;
    private ArenaManager arenaManager;
    private ConfigLoader configLoader;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[CobbleGames] Inicializando mod...");

        // Registrar sonidos del mod (debe hacerse antes de que el servidor cargue recursos)
        MinigameSounds.registerAll();

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStop);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // Eliminar jugadores por desconexión y restaurar su inventario si estaban en partida
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (gameManager != null && handler.getPlayer() != null) {
                gameManager.leaveGame(handler.getPlayer());
                // BUG #6 FIX: limpiar UUID del playerGameMap aunque leaveGame no lo haga
                // (p.ej. si el juego ya terminó pero el UUID quedó registrado)
                gameManager.onPlayerDisconnect(handler.getPlayer().getUuid());
            }
        });

        // CORRECCIÓN: Protección PvP blindada
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity attacker && entity instanceof ServerPlayerEntity victim) {
                if (gameManager != null) {
                    Minigame gameA = gameManager.getPlayerGame(attacker);
                    Minigame gameB = gameManager.getPlayerGame(victim);
                    // Si ALGUNO de los dos está en un minijuego, se bloquea el daño
                    if (gameA != null || gameB != null) {
                        return ActionResult.FAIL;
                    }
                }
            }
            return ActionResult.PASS;
        });

        CommandHandler.register();
        LOGGER.info("[CobbleGames] Mod inicializado correctamente.");
    }

    private void onServerStart(MinecraftServer srv) {
        server = srv;
        configLoader = new ConfigLoader(srv);
        configLoader.loadAll();

        arenaManager = new ArenaManager(configLoader);
        gameManager = new GameManager(arenaManager);

        LOGGER.info("[CobbleGames] Servidor iniciado — {} arenas cargadas.", arenaManager.getArenaCount());
    }

    private void onServerStop(MinecraftServer srv) {
        if (gameManager != null) gameManager.shutdownAll();
    }

    private void onServerTick(MinecraftServer srv) {
        if (gameManager != null) gameManager.tick(srv);
    }

    public static CobbleGames getInstance() { return instance; }
    public static MinecraftServer getServer() { return server; }
    public GameManager getGameManager() { return gameManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public ConfigLoader getConfigLoader() { return configLoader; }
}
