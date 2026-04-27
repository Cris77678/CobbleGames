package com.cobblegames.announce;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;

/**
 * BossBar centralizada para minijuegos.
 * Cada instancia de juego crea una barra propia que se muestra
 * únicamente a los jugadores en partida.
 */
public class GameBossBar {

    private final ServerBossBar bossBar;
    private final Set<UUID> trackedPlayers = new HashSet<>();

    public GameBossBar(String title, BossBar.Color color, BossBar.Style style) {
        this.bossBar = new ServerBossBar(Text.literal(title), color, style);
    }

    /** Agrega un jugador a la bossbar. */
    public void addPlayer(ServerPlayerEntity player) {
        if (trackedPlayers.add(player.getUuid())) {
            bossBar.addPlayer(player);
        }
    }

    /** Quita un jugador de la bossbar (limpieza al salir). */
    public void removePlayer(ServerPlayerEntity player) {
        if (trackedPlayers.remove(player.getUuid())) {
            bossBar.removePlayer(player);
        }
    }

    /** Actualiza el texto de la bossbar. */
    public void setTitle(String title) {
        bossBar.setName(Text.literal(title));
    }

    /** Establece el progreso [0.0 - 1.0]. */
    public void setProgress(float progress) {
        bossBar.setPercent(Math.max(0f, Math.min(1f, progress)));
    }

    /** Cambia el color de la barra. */
    public void setColor(BossBar.Color color) {
        bossBar.setColor(color);
    }

    /** Elimina todos los jugadores y descarta la barra. */
    public void destroy() {
        bossBar.clearPlayers();
        trackedPlayers.clear();
    }

    /** Elimina un jugador por UUID (útil en desconexión). */
    public void removePlayerById(UUID uuid, net.minecraft.server.MinecraftServer server) {
        if (trackedPlayers.remove(uuid)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) bossBar.removePlayer(p);
        }
    }

    public ServerBossBar getRaw() { return bossBar; }
}
