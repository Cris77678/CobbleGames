package com.cobblegames.config;

import com.cobblegames.rewards.RewardConfig;

/**
 * Configuración global del mod CobbleGames.
 * Se carga y guarda en config/cobblegames/settings.json.
 */
public class CobbleGamesSettings {

    // ─── Tiempos de espera ────────────────────────────────────────────────────

    /** Duración en segundos del countdown (STARTING). */
    public int countdownSeconds = 10;

    /** Tiempo en segundos antes de regresar al lobby tras el fin de la partida. */
    public int returnToLobbyDelaySeconds = 5;

    // ─── Juegos ───────────────────────────────────────────────────────────────

    /** Duración por defecto de juegos con tiempo límite (segundos). */
    public int defaultGameDurationSeconds = 120;

    /** Duración de boost en carreras (segundos). */
    public int boostDurationSeconds = 3;

    /** Multiplicador de velocidad durante boost. */
    public double boostSpeedMultiplier = 1.5;

    /** Radio de detección para checkpoints y boosts. */
    public double checkpointRadius = 3.0;

    /** Tiempo base del temporizador Hot Potato (seg). */
    public int hotPotatoBaseTime = 10;

    /** Variación aleatoria del temporizador Hot Potato (seg). */
    public int hotPotatoRandomRange = 10;

    /** Puntos por checkpoint en carreras. */
    public int checkpointPoints = 100;

    /** Puntos extra por boost en carreras. */
    public int boostPoints = 50;

    /** Bonus de puntuación interna por 1.er lugar (carreras). */
    public int firstPlaceBonus = 300;

    /** Bonus de puntuación interna por 2.º lugar (carreras). */
    public int secondPlaceBonus = 150;

    /** Bonus de puntuación interna por 3.er lugar (carreras). */
    public int thirdPlaceBonus = 75;

    // ─── Recompensas (impactor) ───────────────────────────────────────────────

    /** Configuración del sistema de recompensas en moneda impactor. */
    public RewardConfig rewards = new RewardConfig();
}
