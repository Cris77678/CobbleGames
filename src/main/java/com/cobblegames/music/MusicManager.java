package com.cobblegames.music;

import com.cobblegames.announce.AnnouncementSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;

import java.util.List;
import java.util.UUID;

/**
 * Gestor de música de fondo para minijuegos — v2.
 *
 * CARACTERÍSTICAS:
 * ─────────────────────────────────────────────────────────────────────────────
 * • Detección automática de duración: el primer tick que se reproduce una pista
 *   registra el momento; cuando pasan trackDurationTicks vuelve a enviar el sonido
 *   (loop seamless). Si trackDurationTicks es AUTO_DETECT (0), el loop nunca se
 *   dispara y la pista simplemente juega hasta que el cliente la termina — útil si
 *   no conoces la duración exacta.
 *
 * • Fade-out en ticks del servidor (sin hilos externos):
 *   stopWithFade() arranca una cuenta atrás de FADE_TICKS ticks. Cada
 *   FADE_STEP_INTERVAL ticks se reenvía la pista con un volumen más bajo.
 *   Cuando llega a 0 se envía StopSound. Todo ocurre en tick() — hilo principal.
 *
 * • Secuencia countdown → juego → victory → fade:
 *   Gestionada desde AbstractMinigame. MusicManager solo gestiona UNA pista a la
 *   vez; AbstractMinigame crea instancias separadas para countdown, juego y
 *   victory y las llama en orden usando los estados del juego.
 *
 * • Thread-safety: este objeto SOLO se llama desde el hilo del servidor
 *   (ServerTickEvents.END_SERVER_TICK). No usar desde hilos externos.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class MusicManager {

    /** Pasar como trackDurationTicks para deshabilitar el loop automático. */
    public static final int AUTO_DETECT = 0;

    /** Duración del fade-out en ticks (20 = 1 seg). */
    private static final int FADE_TICKS = 60;           // 3 segundos

    /** Cada cuántos ticks se reenvía la pista con volumen reducido durante el fade. */
    private static final int FADE_STEP_INTERVAL = 6;    // 5 pasos de volumen

    /** Volumen mínimo antes de cortar (evita renvíos inaudibles). */
    private static final float FADE_MIN_VOLUME = 0.05f;

    // ─── Estado ───────────────────────────────────────────────────────────────

    private final SoundEvent music;
    private final int trackDurationTicks;
    private final float baseVolume;

    /** Estados internos del manager. */
    private enum State { IDLE, PLAYING, FADING_OUT }

    private State state       = State.IDLE;
    private int   loopCounter = 0;   // ticks desde el último reinicio de la pista
    private int   fadeCounter = 0;   // ticks transcurridos en el fade

    public MusicManager(SoundEvent music, int trackDurationTicks) {
        this(music, trackDurationTicks, 0.65f);
    }

    public MusicManager(SoundEvent music, int trackDurationTicks, float baseVolume) {
        this.music              = music;
        this.trackDurationTicks = trackDurationTicks;
        this.baseVolume         = baseVolume;
    }

    // ─── API pública ──────────────────────────────────────────────────────────

    /** Inicia la pista para todos los jugadores en la lista. */
    public void start(MinecraftServer server, List<UUID> playerIds) {
        state       = State.PLAYING;
        loopCounter = 0;
        fadeCounter = 0;
        AnnouncementSystem.startMusic(server, playerIds, music, baseVolume);
    }

    /**
     * Inicia la pista para un jugador que se une tarde.
     * Si ya está en PLAYING no reinicia el loop global, solo le envía el sonido.
     */
    public void startFor(ServerPlayerEntity player) {
        if (state == State.PLAYING) {
            AnnouncementSystem.startMusicTo(player, music, baseVolume);
        }
    }

    /**
     * Debe llamarse CADA TICK desde AbstractMinigame.onTick() mientras state==INGAME.
     * Gestiona loop automático y fade-out — todo en el hilo del servidor.
     */
    public void tick(MinecraftServer server, List<UUID> playerIds) {
        switch (state) {
            case PLAYING -> tickPlaying(server, playerIds);
            case FADING_OUT -> tickFading(server, playerIds);
            case IDLE -> {}
        }
    }

    /**
     * Detiene la música inmediatamente (sin fade).
     * Usar al limpiar un jugador individual o en situaciones de emergencia.
     */
    public void stop(MinecraftServer server, List<UUID> playerIds) {
        if (state == State.IDLE) return;
        state = State.IDLE;
        AnnouncementSystem.stopMusic(server, playerIds);
    }

    /** Detiene la música para UN jugador (desconexión, salida). Sin fade. */
    public void stopFor(ServerPlayerEntity player) {
        AnnouncementSystem.stopMusicFor(player);
    }

    /**
     * Inicia un fade-out gradual.
     * tick() continuará enviando la pista con volumen decreciente hasta llegar a 0,
     * momento en que enviará StopSound automáticamente.
     * No bloquea ni usa hilos externos.
     */
    public void stopWithFade(MinecraftServer server, List<UUID> playerIds) {
        if (state == State.IDLE) return;
        if (state == State.FADING_OUT) return; // ya está en fade
        state       = State.FADING_OUT;
        fadeCounter = 0;
        // No enviamos StopSound todavía — lo hace tickFading cuando el volumen llega a 0
    }

    public boolean isPlaying()    { return state == State.PLAYING; }
    public boolean isFadingOut()  { return state == State.FADING_OUT; }
    public boolean isIdle()       { return state == State.IDLE; }

    // ─── Tick interno ─────────────────────────────────────────────────────────

    private void tickPlaying(MinecraftServer server, List<UUID> playerIds) {
        // Loop automático si se conoce la duración
        if (trackDurationTicks > 0) {
            loopCounter++;
            if (loopCounter >= trackDurationTicks) {
                loopCounter = 0;
                AnnouncementSystem.startMusic(server, playerIds, music, baseVolume);
            }
        }
    }

    private void tickFading(MinecraftServer server, List<UUID> playerIds) {
        fadeCounter++;

        // Cada FADE_STEP_INTERVAL ticks re-enviamos la pista con menor volumen
        if (fadeCounter % FADE_STEP_INTERVAL == 0) {
            float progress = (float) fadeCounter / FADE_TICKS;         // 0.0 → 1.0
            float vol      = baseVolume * (1f - progress);              // baseVolume → 0

            if (vol <= FADE_MIN_VOLUME || fadeCounter >= FADE_TICKS) {
                // Fade completado — cortar definitivamente
                state = State.IDLE;
                AnnouncementSystem.stopMusic(server, playerIds);
            } else {
                // Re-enviar la pista al volumen reducido.
                // El cliente reemplaza el canal existente de la misma pista
                // con la nueva petición de volumen más bajo.
                AnnouncementSystem.startMusic(server, playerIds, music, vol);
            }
        }
    }
}
