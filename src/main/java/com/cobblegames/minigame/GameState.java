package com.cobblegames.minigame;

/**
 * Estados posibles de una partida de minijuego.
 *
 * <pre>
 *  WAITING ──► STARTING ──► INGAME ──► ENDING
 *      ▲                                  │
 *      └──────────────────────────────────┘  (vuelve a WAITING para próxima ronda)
 * </pre>
 */
public enum GameState {

    /** Esperando que se unan suficientes jugadores. */
    WAITING,

    /** Cuenta regresiva antes de comenzar (10 segundos por defecto). */
    STARTING,

    /** Partida en curso — onTick() activo. */
    INGAME,

    /** Finalizando — mostrando ganador y teleportando al lobby. */
    ENDING
}
