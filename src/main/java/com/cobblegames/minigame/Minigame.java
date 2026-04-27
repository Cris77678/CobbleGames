package com.cobblegames.minigame;

import com.cobblegames.arena.Arena;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.UUID;

/**
 * Contrato base que todo minijuego debe implementar.
 *
 * El ciclo de vida de un minijuego sigue el patrón:
 *   register() → onPlayerJoin() × N → onStart() → onTick() × T → onEnd()
 *
 * Los nuevos minijuegos solo necesitan extender {@link AbstractMinigame}
 * (que provee implementaciones por defecto) y sobreescribir la lógica propia.
 */
public interface Minigame {

    /** Identificador único del tipo de minijuego (ej. "race_ground", "hot_potato"). */
    String getId();

    /** Nombre legible para mostrar a los jugadores. */
    String getDisplayName();

    /** Número mínimo de jugadores para iniciar la partida. */
    int getMinPlayers();

    /** Número máximo de jugadores permitidos. */
    int getMaxPlayers();

    /** Arena en la que transcurre esta instancia. */
    Arena getArena();

    /** Estado actual de la partida. */
    GameState getState();

    /** Lista de UUIDs de los jugadores actualmente en la partida. */
    List<UUID> getPlayers();

    // ─── Ciclo de vida ────────────────────────────────────────────────────────

    /**
     * Llamado cuando un jugador solicita unirse.
     * Debe rechazar si la partida ya está en curso o está llena.
     *
     * @return true si el jugador fue admitido.
     */
    boolean onPlayerJoin(ServerPlayerEntity player);

    /**
     * Llamado cuando un jugador abandona voluntariamente o se desconecta.
     */
    void onPlayerLeave(ServerPlayerEntity player);

    /**
     * Inicia la partida — teleporta jugadores, configura el mundo y cambia a STARTING.
     */
    void onStart(MinecraftServer server);

    /**
     * Llamado en cada tick del servidor mientras la partida está en INGAME.
     * Aquí va la lógica central de cada minijuego.
     */
    void onTick(MinecraftServer server);

    /**
     * Finaliza la partida, anuncia el ganador, teleporta a lobby y limpia estado.
     */
    void onEnd(MinecraftServer server, EndReason reason);

    // ─── Eventos opcionales (con implementación vacía en AbstractMinigame) ────

    /** Llamado cuando un jugador muere dentro de la partida. */
    default void onPlayerDeath(ServerPlayerEntity player) {}

    /** Llamado cuando un jugador golpea a otro. */
    default void onPlayerHit(ServerPlayerEntity attacker, ServerPlayerEntity victim) {}
}
