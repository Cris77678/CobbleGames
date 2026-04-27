package com.cobblegames.minigame;

import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.announce.GameBossBar;
import com.cobblegames.arena.Arena;
import com.cobblegames.config.CobbleGamesSettings;
import com.cobblegames.core.CobbleGames;
import com.cobblegames.music.MusicManager;
import com.cobblegames.music.MinigameSounds;
import com.cobblegames.rewards.RewardSystem;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.*;

/**
 * Base de todos los minijuegos — v2.2
 *
 * SECUENCIA DE MÚSICA (todo en el hilo del servidor, sin hilos externos):
 * ─────────────────────────────────────────────────────────────────────────────
 *  WAITING   → silencio
 *  STARTING  → countdown.ogg  (se detiene al cancelar o al pasar a INGAME)
 *  INGAME    → pista del juego (loop automático si trackDurationTicks > 0)
 *              al terminar el juego:
 *                1. fade-out de la pista actual (tick a tick, ~3 seg)
 *                2. cuando el fade termina, arranca victory.ogg
 *                3. victory.ogg suena durante endingTicks; al llegar a 0 se
 *                   hace fade-out de victory y se va al lobby
 *  ENDING    → victory.ogg con fade-out al final
 *
 * THREAD-SAFETY: toda la lógica de música ocurre en onTick() que el servidor
 * llama desde su hilo principal. Nunca se crean hilos nuevos.
 */
public abstract class AbstractMinigame implements Minigame {

    private static final int TICKS_PER_SECOND = 20;

    protected final Arena arena;
    protected final List<UUID> players          = new ArrayList<>();
    protected final Set<UUID>  eliminatedPlayers = new HashSet<>();
    protected GameState state   = GameState.WAITING;
    protected int       gameTick = 0;

    private int     countdownTicks;
    private int     endingTicks;
    private boolean fullyEnded = false;

    // Inventario / estado del jugador
    private final Map<UUID, NbtList>  savedInventories = new HashMap<>();
    private final Map<UUID, Integer>  savedExpLevels   = new HashMap<>();
    private final Map<UUID, Float>    savedExpProgress = new HashMap<>();
    private final Map<UUID, GameMode> savedGameModes   = new HashMap<>();
    private final Map<UUID, Float>    savedHealth      = new HashMap<>();
    private final Map<UUID, Integer>  savedFoodLevel   = new HashMap<>();
    private final Map<UUID, Float>    savedSaturation  = new HashMap<>();

    private GameBossBar bossBar;
    protected RewardSystem rewardSystem;

    // ── Música ────────────────────────────────────────────────────────────────
    /** Manager de la pista principal del juego (asignado por setMusic en subclase). */
    private MusicManager gameMusic;

    /** Manager de la pista de countdown (MusicManager.AUTO_DETECT → no hace loop). */
    private final MusicManager countdownMusic =
        new MusicManager(MinigameSounds.MUSIC_COUNTDOWN, MusicManager.AUTO_DETECT, 0.65f);

    /** Manager de la pista de victoria. */
    private final MusicManager victoryMusic =
        new MusicManager(MinigameSounds.MUSIC_VICTORY, MusicManager.AUTO_DETECT, 0.8f);

    /**
     * Fases internas de la transición de música durante ENDING.
     * FADING_GAME  → gameMusic está en fade-out, esperando que termine
     * PLAYING_VICTORY → victoryMusic sonando
     * FADING_VICTORY → victoryMusic en fade-out antes de volver al lobby
     */
    private enum MusicPhase { NONE, FADING_GAME, PLAYING_VICTORY, FADING_VICTORY }
    private MusicPhase musicPhase = MusicPhase.NONE;

    // ─── Constructor ──────────────────────────────────────────────────────────

    protected AbstractMinigame(Arena arena) {
        this.arena = arena;
        CobbleGamesSettings s = CobbleGames.getInstance().getConfigLoader().getSettings();
        this.countdownTicks = s.countdownSeconds * TICKS_PER_SECOND;
        this.endingTicks    = s.returnToLobbyDelaySeconds * TICKS_PER_SECOND;
        this.rewardSystem   = new RewardSystem(s.rewards);
    }

    /**
     * Las subclases llaman a este método en su constructor para registrar
     * la pista del juego.
     *
     * @param sound              SoundEvent del juego
     * @param trackDurationTicks Duración del .ogg en ticks para loop.
     *                           Usar MusicManager.AUTO_DETECT (0) si no se conoce.
     */
    protected final void setMusic(SoundEvent sound, int trackDurationTicks) {
        this.gameMusic = new MusicManager(sound, trackDurationTicks);
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public boolean isFullyEnded()            { return fullyEnded; }
    @Override public Arena getArena()        { return arena; }
    @Override public GameState getState()    { return state; }
    @Override public List<UUID> getPlayers() { return Collections.unmodifiableList(players); }

    // ─── Validación ───────────────────────────────────────────────────────────

    protected void validate(ValidationResult result) {
        if (arena.getLobbyPos() == null || arena.getLobbyPos().equals(BlockPos.ORIGIN))
            result.addError("Lobby pos no configurada.");
        if (arena.getSpawnPoints().isEmpty())
            result.addError("Sin puntos de spawn.");
    }

    // ─── Join / Leave ─────────────────────────────────────────────────────────

    @Override
    public boolean onPlayerJoin(ServerPlayerEntity player) {
        if (state != GameState.WAITING && state != GameState.STARTING) {
            player.sendMessage(Text.literal("§cLa partida ya está en curso."), false);
            return false;
        }
        if (players.size() >= getMaxPlayers()) {
            player.sendMessage(Text.literal("§cLa partida está llena."), false);
            return false;
        }
        players.add(player.getUuid());
        if (bossBar != null) bossBar.addPlayer(player);

        // Si ya está en countdown, el jugador recibe la pista de countdown
        if (state == GameState.STARTING) countdownMusic.startFor(player);

        AnnouncementSystem.announce(CobbleGames.getServer(), players,
            "§a" + player.getName().getString() + " §7se unió. ("
            + players.size() + "/" + getMaxPlayers() + ")");

        if (players.size() >= getMinPlayers() && state == GameState.WAITING)
            startCountdown(CobbleGames.getServer());
        return true;
    }

    @Override
    public void onPlayerLeave(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        players.remove(uuid);
        eliminatedPlayers.remove(uuid);
        cleanupPlayer(player);

        if (state == GameState.STARTING) {
            if (players.size() < getMinPlayers()) cancelCountdown(CobbleGames.getServer());
        } else if (state == GameState.INGAME) {
            if (getActivePlayers(CobbleGames.getServer()).size() < getMinPlayers())
                onEnd(CobbleGames.getServer(), EndReason.NOT_ENOUGH_PLAYERS);
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public final void onStart(MinecraftServer server) {
        ValidationResult v = new ValidationResult();
        validate(v);
        if (!v.isValid()) {
            AnnouncementSystem.announce(server, players, v.formatErrors(getDisplayName()));
            state = GameState.ENDING;
            returnPlayersToLobby(server);
            return;
        }
        if (server.getWorld(arena.getWorldKey()) == null) {
            AnnouncementSystem.announce(server, players,
                "§c[" + getDisplayName() + "] Error: el mundo '" + arena.getWorldId() + "' no está cargado.");
            state = GameState.ENDING;
            returnPlayersToLobby(server);
            return;
        }

        // Detener countdown con fade antes de iniciar la pista del juego
        countdownMusic.stopWithFade(server, new ArrayList<>(players));
        // La pista del juego empezará en onGameStart una vez que el fade del
        // countdown termine — ver tickEnding() lógica análoga, pero aquí lo
        // hacemos inmediatamente ya que el fade del countdown es corto y
        // se superpone limpiamente con el inicio del juego.
        // (El cliente gestiona el canal de música; la superposición es intencional
        //  y suena a crossfade natural.)

        state    = GameState.INGAME;
        gameTick = 0;
        fullyEnded   = false;
        musicPhase   = MusicPhase.NONE;

        initBossBar(server);

        List<BlockPos> spawns   = arena.getSpawnPoints();
        List<UUID>     snapshot = new ArrayList<>(players);
        for (int i = 0; i < snapshot.size(); i++) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(snapshot.get(i));
            if (player == null) continue;
            savePlayerState(player);
            preparePlayer(player);
            BlockPos spawn = spawns.isEmpty() ? arena.getLobbyPos() : spawns.get(i % spawns.size());
            player.teleport(server.getWorld(arena.getWorldKey()),
                spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
        }

        AnnouncementSystem.announceTitle(server, players, "§a¡INICIO!", "§e" + getDisplayName());
        if (gameMusic != null) gameMusic.start(server, players);
        onGameStart(server);
    }

    @Override
    public final void onTick(MinecraftServer server) {
        switch (state) {
            case STARTING -> tickCountdown(server);
            case INGAME   -> {
                gameTick++;
                // Tick de la pista del juego (loop automático) — solo en INGAME
                if (gameMusic != null) gameMusic.tick(server, new ArrayList<>(players));
                onGameTick(server);
                updateBossBarIngame(server);
            }
            case ENDING -> tickEnding(server);
            default -> {}
        }
    }

    @Override
    public final void onEnd(MinecraftServer server, EndReason reason) {
        if (state == GameState.ENDING || fullyEnded) return;
        state = GameState.ENDING;

        CobbleGamesSettings s = CobbleGames.getInstance().getConfigLoader().getSettings();
        this.endingTicks = s.returnToLobbyDelaySeconds * TICKS_PER_SECOND;

        if (bossBar != null) {
            bossBar.setTitle("§7Terminando partida...");
            bossBar.setProgress(0f);
            bossBar.setColor(BossBar.Color.WHITE);
        }

        // Iniciar secuencia de música de fin:
        // 1. Si hay pista de juego → fade-out
        // 2. Cuando termina el fade → victory
        // 3. Al llegar endingTicks a 0 → fade-out de victory → lobby
        if (gameMusic != null && gameMusic.isPlaying()) {
            musicPhase = MusicPhase.FADING_GAME;
            gameMusic.stopWithFade(server, new ArrayList<>(players));
        } else if (reason == EndReason.WINNER_FOUND || reason == EndReason.TIME_OVER) {
            // No había pista de juego → pasar directo a victory
            musicPhase = MusicPhase.PLAYING_VICTORY;
            victoryMusic.start(server, players);
        } else {
            musicPhase = MusicPhase.NONE;
        }

        onGameEnd(server, reason);
    }

    // ─── Tick de ENDING ──────────────────────────────────────────────────────

    private void tickEnding(MinecraftServer server) {
        List<UUID> snapshot = new ArrayList<>(players);

        // Avanzar la máquina de estados de música
        switch (musicPhase) {
            case FADING_GAME -> {
                // Continuar el fade-out del juego
                if (gameMusic != null) gameMusic.tick(server, snapshot);
                // Cuando el fade termina, arrancar victory
                if (gameMusic == null || gameMusic.isIdle()) {
                    musicPhase = MusicPhase.PLAYING_VICTORY;
                    victoryMusic.start(server, players);
                }
            }
            case PLAYING_VICTORY -> {
                // Dejar sonar victory; cuando endingTicks está a punto de llegar
                // a 0 empezamos el fade de victory
                victoryMusic.tick(server, snapshot);
                // Iniciar fade de victory cuando quedan ~3 seg (60 ticks = duración del fade)
                if (endingTicks <= 60 && !victoryMusic.isFadingOut()) {
                    musicPhase = MusicPhase.FADING_VICTORY;
                    victoryMusic.stopWithFade(server, snapshot);
                }
            }
            case FADING_VICTORY -> {
                // Continuar el fade-out de victory
                victoryMusic.tick(server, snapshot);
            }
            case NONE -> {}
        }

        // Contador de tiempo antes de volver al lobby
        endingTicks--;
        if (endingTicks <= 0) {
            // Asegurar que toda música está detenida antes de limpiar
            if (gameMusic != null)   gameMusic.stop(server, snapshot);
            victoryMusic.stop(server, snapshot);
            returnPlayersToLobby(server);
        }
    }

    // ─── Métodos abstractos ───────────────────────────────────────────────────

    protected abstract void onGameStart(MinecraftServer server);
    protected abstract void onGameTick(MinecraftServer server);
    protected abstract void onGameEnd(MinecraftServer server, EndReason reason);

    protected String getBossBarTitle(MinecraftServer server) { return "§e" + getDisplayName(); }
    protected float  getBossBarProgress(MinecraftServer server) { return -1f; }

    // ─── BossBar ──────────────────────────────────────────────────────────────

    private void initBossBar(MinecraftServer server) {
        if (bossBar != null) bossBar.destroy();
        bossBar = new GameBossBar("§e" + getDisplayName(), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        for (UUID uuid : players) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        }
    }

    private void updateBossBarIngame(MinecraftServer server) {
        if (bossBar == null || gameTick % 20 != 0) return;
        bossBar.setTitle(getBossBarTitle(server));
        float p = getBossBarProgress(server);
        if (p >= 0) bossBar.setProgress(p);
    }

    // ─── Countdown ────────────────────────────────────────────────────────────

    private void startCountdown(MinecraftServer server) {
        state = GameState.STARTING;
        CobbleGamesSettings s = CobbleGames.getInstance().getConfigLoader().getSettings();
        countdownTicks = s.countdownSeconds * TICKS_PER_SECOND;

        if (bossBar != null) bossBar.destroy();
        bossBar = new GameBossBar("§eEsperando...", BossBar.Color.GREEN, BossBar.Style.PROGRESS);
        for (UUID uuid : players) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) bossBar.addPlayer(p);
        }

        countdownMusic.start(server, players);

        AnnouncementSystem.announceTitle(server, players,
            "§eInicia en §c" + s.countdownSeconds + "s", "§7¡Prepárate!");
    }

    private void cancelCountdown(MinecraftServer server) {
        state = GameState.WAITING;
        // Fade-out del countdown al cancelar (suena mejor que corte brusco)
        countdownMusic.stopWithFade(server, new ArrayList<>(players));
        if (bossBar != null) { bossBar.destroy(); bossBar = null; }
        AnnouncementSystem.announce(server, players,
            "§c¡Cancelado! Faltan jugadores (" + getMinPlayers() + " mínimo).");
    }

    private void tickCountdown(MinecraftServer server) {
        // Tick de la música de countdown (no hace loop, pero procesa fade-out si fue cancelado)
        countdownMusic.tick(server, new ArrayList<>(players));

        countdownTicks--;
        CobbleGamesSettings s = CobbleGames.getInstance().getConfigLoader().getSettings();
        int total    = s.countdownSeconds * TICKS_PER_SECOND;
        float progress = (float) countdownTicks / total;

        if (bossBar != null) {
            int sec = countdownTicks / TICKS_PER_SECOND;
            bossBar.setTitle("§eIniciando §c" + sec + "s §7| §f" + players.size() + "/" + getMaxPlayers());
            bossBar.setProgress(Math.max(0f, progress));
        }

        int secsLeft = countdownTicks / TICKS_PER_SECOND;
        if (countdownTicks % TICKS_PER_SECOND == 0 && secsLeft > 0 && secsLeft <= 5)
            AnnouncementSystem.announceActionBar(server, players, "§eInicia en §c" + secsLeft + "s");

        if (countdownTicks <= 0) onStart(server);
    }

    // ─── Limpieza de jugador ──────────────────────────────────────────────────

    /**
     * Limpieza completa para un jugador que sale o se desconecta.
     * Detiene TODA la música de forma inmediata para ese jugador (sin fade,
     * porque ya no está en partida y no queremos que siga oyendo música del juego).
     */
    private void cleanupPlayer(ServerPlayerEntity player) {
        if (bossBar != null) bossBar.removePlayer(player);
        // Detener TODA música para este jugador (juego, countdown, victory)
        if (gameMusic != null)    gameMusic.stopFor(player);
        countdownMusic.stopFor(player);
        victoryMusic.stopFor(player);
        player.clearStatusEffects();
        restorePlayerState(player);
    }

    private void savePlayerState(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        NbtList inv = new NbtList();
        player.getInventory().writeNbt(inv);
        savedInventories.put(uuid, inv);
        savedExpLevels.put(uuid, player.experienceLevel);
        savedExpProgress.put(uuid, player.experienceProgress);
        savedGameModes.put(uuid, player.interactionManager.getGameMode());
        savedHealth.put(uuid, player.getHealth());
        savedFoodLevel.put(uuid, player.getHungerManager().getFoodLevel());
        savedSaturation.put(uuid, player.getHungerManager().getSaturationLevel());
    }

    private void preparePlayer(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.setExperienceLevel(0);
        player.setExperiencePoints(0);
        player.setHealth(20.0f);
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0f);
        player.clearStatusEffects();
        player.changeGameMode(GameMode.ADVENTURE);
    }

    private void restorePlayerState(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        player.clearStatusEffects();
        if (savedInventories.containsKey(uuid)) {
            player.getInventory().clear();
            player.getInventory().readNbt(savedInventories.remove(uuid));
            player.experienceLevel    = savedExpLevels.remove(uuid);
            player.experienceProgress = savedExpProgress.remove(uuid);
            GameMode gm = savedGameModes.remove(uuid);
            player.changeGameMode(gm != null ? gm : GameMode.SURVIVAL);
            float hp = savedHealth.getOrDefault(uuid, 20f);
            savedHealth.remove(uuid);
            player.setHealth(Math.max(1f, hp));
            int food = savedFoodLevel.getOrDefault(uuid, 20);
            savedFoodLevel.remove(uuid);
            player.getHungerManager().setFoodLevel(food);
            float sat = savedSaturation.getOrDefault(uuid, 5f);
            savedSaturation.remove(uuid);
            player.getHungerManager().setSaturationLevel(sat);
        }
    }

    // ─── Vuelta al lobby ──────────────────────────────────────────────────────

    public void returnPlayersToLobby(MinecraftServer server) {
        if (fullyEnded) return;
        fullyEnded = true;
        BlockPos lobby = arena.getLobbyPos();
        List<UUID> snapshot = new ArrayList<>(players);
        for (UUID uuid : snapshot) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                var world = server.getWorld(arena.getWorldKey());
                if (world != null)
                    p.teleport(world, lobby.getX() + 0.5, lobby.getY(), lobby.getZ() + 0.5, 0f, 0f);
                cleanupPlayer(p);
            }
        }
        if (bossBar != null) { bossBar.destroy(); bossBar = null; }
    }

    // ─── Utilidades ───────────────────────────────────────────────────────────

    protected List<ServerPlayerEntity> getActivePlayers(MinecraftServer server) {
        List<ServerPlayerEntity> active = new ArrayList<>();
        for (UUID uuid : players) {
            if (eliminatedPlayers.contains(uuid)) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) active.add(p);
        }
        return active;
    }

    protected void eliminatePlayer(MinecraftServer server, ServerPlayerEntity player, String message) {
        eliminatedPlayers.add(player.getUuid());
        AnnouncementSystem.announceTo(player, message);
        AnnouncementSystem.announce(server, players,
            "§c" + player.getName().getString() + " §7fue eliminado.");
        player.changeGameMode(GameMode.SPECTATOR);
        if (state == GameState.INGAME) {
            List<ServerPlayerEntity> active = getActivePlayers(server);
            if (active.size() <= 1)
                onEnd(server, active.isEmpty() ? EndReason.TIME_OVER : EndReason.WINNER_FOUND);
        }
    }

    protected GameBossBar getBossBar() { return bossBar; }
}
