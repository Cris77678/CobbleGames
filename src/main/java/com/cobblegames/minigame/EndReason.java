package com.cobblegames.minigame;

/** Motivo por el que termina una partida. */
public enum EndReason {
    /** Condición de victoria cumplida (hay un ganador). */
    WINNER_FOUND,
    /** Se agotó el tiempo del juego. */
    TIME_OVER,
    /** No quedaron suficientes jugadores para continuar. */
    NOT_ENOUGH_PLAYERS,
    /** Administrador forzó el fin con /cg stop. */
    ADMIN_STOP
}
