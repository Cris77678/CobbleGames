package com.cobblegames.music;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Registro de todos los SoundEvent del mod CobbleGames.
 *
 * Los archivos .ogg deben colocarse en:
 *   src/main/resources/assets/cobblegames/sounds/minigame/music/
 *
 * Nombres de archivo esperados:
 *   coin_collector.ogg
 *   freeze_tag.ogg
 *   hot_potato.ogg
 *   king_of_hill.ogg
 *   red_light.ogg
 *   race.ogg
 *   countdown.ogg  (opcional, efecto de inicio)
 *   victory.ogg    (opcional, fanfarria de victoria)
 *
 * Si el .ogg no existe, el sonido simplemente no se reproducirá —
 * el mod NO fallará al cargar.
 */
public final class MinigameSounds {

    // ── Músicas de juego ─────────────────────────────────────────────────────
    public static final SoundEvent MUSIC_COIN_COLLECTOR = register("minigame.music.coin_collector");
    public static final SoundEvent MUSIC_FREEZE_TAG     = register("minigame.music.freeze_tag");
    public static final SoundEvent MUSIC_HOT_POTATO     = register("minigame.music.hot_potato");
    public static final SoundEvent MUSIC_KING_OF_HILL   = register("minigame.music.king_of_hill");
    public static final SoundEvent MUSIC_RED_LIGHT      = register("minigame.music.red_light");
    public static final SoundEvent MUSIC_RACE           = register("minigame.music.race");

    // ── Efectos globales ─────────────────────────────────────────────────────
    /** Música/jingle durante el countdown (STARTING). */
    public static final SoundEvent MUSIC_COUNTDOWN      = register("minigame.music.countdown");
    /** Fanfarria corta al terminar la partida. */
    public static final SoundEvent MUSIC_VICTORY        = register("minigame.music.victory");

    private MinigameSounds() {}

    /** Llamar desde CobbleGames.onInitialize() para activar el registro. */
    public static void registerAll() {
        // El registro ocurre en los inicializadores estáticos de arriba;
        // este método solo fuerza la carga de la clase.
    }

    private static SoundEvent register(String path) {
        Identifier id = Identifier.of("cobblegames", path);
        SoundEvent event = SoundEvent.of(id);
        Registry.register(Registries.SOUND_EVENT, id, event);
        return event;
    }
}
