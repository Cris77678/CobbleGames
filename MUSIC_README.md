# CobbleGames — Sistema de Música

## Cómo añadir música a cada minijuego

Los archivos de sonido deben ser `.ogg` (Vorbis) y colocarse en:

```
src/main/resources/assets/cobblegames/sounds/minigame/music/
```

### Archivos esperados

| Archivo              | Minijuego             | Loop en AbstractMinigame |
|---------------------|-----------------------|--------------------------|
| `coin_collector.ogg` | Coin Collector        | 2 min                    |
| `freeze_tag.ogg`     | Freeze Tag            | 2 min                    |
| `hot_potato.ogg`     | Hot Potato            | 1 min (pista tensa/corta)|
| `king_of_hill.ogg`   | King of the Hill      | 3 min                    |
| `red_light.ogg`      | Luz Roja / Luz Verde  | 2.5 min                  |
| `race.ogg`           | Carrera (tierra/aire) | 5 min                    |
| `countdown.ogg`      | Pantalla de espera    | sin loop (efecto corto)  |
| `victory.ogg`        | Fanfarria de victoria | sin loop (efecto corto)  |

> Si un archivo no existe, el sonido simplemente no se reproducirá.  
> El mod **no fallará** al iniciar aunque falten archivos.

## Convertir música a .ogg

Usando **Audacity** (gratis):
1. Archivo → Exportar → Exportar como OGG Vorbis
2. Calidad recomendada: 5 (buen balance tamaño/calidad)
3. Canales: Mono para efectos cortos, Estéreo para música de fondo

Usando **ffmpeg** (línea de comandos):
```bash
ffmpeg -i musica.mp3 -c:a libvorbis -q:a 5 coin_collector.ogg
```

## Ajustar duración del loop

Si la pista dura diferente a lo configurado, edita el constructor del juego:

```java
// En CoinCollectorGame.java
public CoinCollectorGame(Arena arena) {
    super(arena);
    // Cambiar 20 * 120 por la duración real en ticks (20 ticks = 1 segundo)
    setMusic(MinigameSounds.MUSIC_COIN_COLLECTOR, 20 * 180); // pista de 3 min
}
```

## Volumen

El volumen de la música respeta el **control de volumen de Música** del cliente.  
Canal usado: `SoundCategory.MUSIC` (el mismo que la música de fondo de Minecraft).

Para cambiar el volumen por defecto del servidor, edita `MusicManager`:
```java
new MusicManager(sound, trackDurationTicks, 0.8f); // 0.0 - 1.0
```
