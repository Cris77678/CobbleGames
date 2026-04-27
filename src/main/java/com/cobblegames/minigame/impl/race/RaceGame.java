package com.cobblegames.minigame.impl.race;

import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.arena.Arena;
import com.cobblegames.music.MinigameSounds;
import com.cobblegames.config.CobbleGamesSettings;
import com.cobblegames.core.CobbleGames;
import com.cobblegames.minigame.AbstractMinigame;
import com.cobblegames.minigame.EndReason;
import com.cobblegames.minigame.ValidationResult;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.stream.Collectors;

public class RaceGame extends AbstractMinigame {

    public enum RaceType { GROUND, FLYING }

    private static final double CHECKPOINT_RADIUS = 3.0;
    private static final int    DEFAULT_LAPS      = 1;
    private static final int    MAX_GAME_TICKS    = 20 * 300;

    private final RaceType raceType;
    private final CobbleGamesSettings settings;
    private final int totalLaps;

    private final Map<UUID, Integer> checkpointProgress = new LinkedHashMap<>();
    private final Map<UUID, Integer> lapsCompleted      = new LinkedHashMap<>();
    private final Map<UUID, Integer> scores             = new LinkedHashMap<>();
    private final List<UUID> finishOrder                = new ArrayList<>();
    private final Map<UUID, Integer> racePositions      = new LinkedHashMap<>();
    // BUG #9 FIX: set de jugadores que ya terminaron la carrera (no se eliminan, se "gradúan")
    private final Set<UUID> finishedPlayers             = new HashSet<>();

    private BoostSystem boostSystem;
    private List<BlockPos> checkpoints;
    private final List<Entity> checkpointMarkers = new ArrayList<>();

    public RaceGame(Arena arena, RaceType type)           { this(arena, type, DEFAULT_LAPS); }
    public RaceGame(Arena arena, RaceType type, int laps) {
        super(arena);
        this.raceType  = type;
        this.totalLaps = laps;
        this.settings  = CobbleGames.getInstance().getConfigLoader().getSettings();
        setMusic(MinigameSounds.MUSIC_RACE, 20 * 300);
    }

    @Override public String getId() { return raceType == RaceType.GROUND ? "race_ground" : "race_flying"; }
    @Override public String getDisplayName() { return raceType == RaceType.GROUND ? "Carrera Terrestre" : "Carrera Aérea"; }
    @Override public int getMinPlayers() { return 2; }
    @Override public int getMaxPlayers() { return 8; }

    @Override
    protected void validate(ValidationResult result) {
        super.validate(result);
        // BUG #4 FIX: lógica de validación corregida — orden importa
        int cpCount = arena.getCheckpoints().size();
        if (cpCount == 0) {
            result.addError("Sin checkpoints. Añade al menos 2 con /cg arena addcheckpoint.");
        } else if (cpCount == 1) {
            result.addError("Solo hay 1 checkpoint — se necesitan al menos 2 para una carrera.");
        }
        if (raceType == RaceType.FLYING && arena.getSpawnPoints().isEmpty())
            result.addError("Carrera aérea requiere puntos de spawn elevados.");
    }

    @Override
    public boolean onPlayerJoin(ServerPlayerEntity player) {
        if (arena.getCheckpoints().size() < 2) {
            player.sendMessage(Text.literal("§cError: La pista necesita al menos 2 checkpoints."), false);
            return false;
        }
        return super.onPlayerJoin(player);
    }

    @Override
    protected void onGameStart(MinecraftServer server) {
        checkpoints = new ArrayList<>(arena.getCheckpoints());
        ServerWorld world = server.getWorld(arena.getWorldKey());

        // BUG #5 FIX: guardar referencia de markers ANTES de cualquier fallo posible
        if (world != null) {
            for (BlockPos cp : checkpoints) {
                ShulkerEntity marker = EntityType.SHULKER.create(world);
                if (marker != null) {
                    marker.setPosition(cp.getX() + 0.5, cp.getY(), cp.getZ() + 0.5);
                    marker.setInvisible(true);
                    marker.setNoGravity(true);
                    marker.setAiDisabled(true);
                    marker.setInvulnerable(true);
                    marker.addCommandTag("cg_race_marker");
                    marker.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, -1, 0, false, false, false));
                    world.spawnEntity(marker);
                    checkpointMarkers.add(marker);  // añadir ANTES del spawn para garantizar limpieza
                }
            }
        }

        for (UUID uuid : players) {
            checkpointProgress.put(uuid, 0);
            lapsCompleted.put(uuid, 0);
            scores.put(uuid, 0);
        }

        boostSystem = new BoostSystem(
            arena.getBoostPositions(),
            arena.getBoostPositions().size() > 3 ? BoostSystem.BoostMode.DYNAMIC : BoostSystem.BoostMode.STATIC,
            settings);

        AnnouncementSystem.announce(server, players,
            "§e¡Carrera §6" + totalLaps + " §evuelta(s)! Checkpoints: §a" + checkpoints.size()
            + (arena.getBoostPositions().isEmpty() ? "" : " §7| Boosts: §6⚡"));
    }

    @Override
    protected void onGameTick(MinecraftServer server) {
        if (gameTick > MAX_GAME_TICKS) { onEnd(server, EndReason.TIME_OVER); return; }
        ServerWorld world = server.getWorld(arena.getWorldKey());
        if (world == null) return;

        List<ServerPlayerEntity> active = getActivePlayers(server);
        // BUG #9 FIX: excluir jugadores que ya terminaron del tick de checkpoints
        List<ServerPlayerEntity> racing = active.stream()
            .filter(p -> !finishedPlayers.contains(p.getUuid()))
            .collect(java.util.stream.Collectors.toList());

        if (!arena.getBoostPositions().isEmpty()) boostSystem.tick(server, world, racing, scores);
        for (ServerPlayerEntity player : racing) tickPlayerCheckpoints(server, player);
        updateRacePositions();
        if (gameTick % 10 == 0) active.forEach(this::sendRaceHUD);
    }

    private void tickPlayerCheckpoints(MinecraftServer server, ServerPlayerEntity player) {
        UUID uuid     = player.getUuid();
        int nextIndex = checkpointProgress.getOrDefault(uuid, 0);

        // BUG #2 FIX: solo completar vuelta cuando nextIndex == checkpoints.size(), no >=
        // y proteger con un flag para que no se llame múltiples veces
        if (nextIndex == checkpoints.size()) {
            completeLap(server, player);
            // resetear para la siguiente vuelta INMEDIATAMENTE para evitar re-entrada
            checkpointProgress.put(uuid, 0);
            return;
        }

        if (nextIndex < 0 || nextIndex >= checkpoints.size()) return;

        BlockPos nextCp = checkpoints.get(nextIndex);
        if (isNearPos(player, nextCp, CHECKPOINT_RADIUS)) {
            checkpointProgress.put(uuid, nextIndex + 1);
            scores.merge(uuid, settings.checkpointPoints, Integer::sum);
            AnnouncementSystem.announceActionBarTo(player,
                "§a✓ Checkpoint §e" + (nextIndex + 1) + "/" + checkpoints.size()
                + "  §7+§a" + settings.checkpointPoints + "pts");
        }
    }

    private void completeLap(MinecraftServer server, ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        int laps  = lapsCompleted.merge(uuid, 1, Integer::sum);
        if (laps >= totalLaps) {
            playerFinished(server, player);
        } else {
            AnnouncementSystem.announce(server, players,
                "§e" + player.getName().getString() + " §7completó la vuelta §a" + laps + "/" + totalLaps);
        }
    }

    private void playerFinished(MinecraftServer server, ServerPlayerEntity player) {
        if (finishedPlayers.contains(player.getUuid())) return; // guard doble llamada
        int position = finishOrder.size() + 1;
        finishOrder.add(player.getUuid());
        finishedPlayers.add(player.getUuid());

        int bonus = switch (position) {
            case 1 -> settings.firstPlaceBonus;
            case 2 -> settings.secondPlaceBonus;
            case 3 -> settings.thirdPlaceBonus;
            default -> 0;
        };
        scores.merge(player.getUuid(), bonus, Integer::sum);

        String medal = switch (position) {
            case 1 -> "§6🥇 1.º"; case 2 -> "§7🥈 2.º"; case 3 -> "§c🥉 3.º";
            default -> "§7" + position + ".º";
        };
        AnnouncementSystem.announceTitle(server, players,
            medal + " §e" + player.getName().getString(),
            bonus > 0 ? "§7+" + bonus + " pts de bonificación" : "§7¡Terminó la carrera!");

        // Terminar si todos los jugadores activos terminaron
        long stillRacing = players.stream()
            .filter(uuid -> !finishedPlayers.contains(uuid) && !eliminatedPlayers.contains(uuid))
            .count();
        if (stillRacing == 0) onEnd(server, EndReason.WINNER_FOUND);
    }

    @Override
    protected void onGameEnd(MinecraftServer server, EndReason reason) {
        // BUG #5 FIX: limpiar markers siempre, incluso si la lista estaba vacía
        checkpointMarkers.forEach(Entity::discard);
        checkpointMarkers.clear();

        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(scores.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        StringBuilder sb = new StringBuilder("§e=== Resultados de la Carrera ===\n");
        int pos = 1;
        for (Map.Entry<UUID, Integer> entry : ranking) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
            String name = p != null ? p.getName().getString() : entry.getKey().toString();
            String medal = switch (pos) { case 1 -> "§6🥇"; case 2 -> "§7🥈"; case 3 -> "§c🥉"; default -> "§7" + pos + "."; };
            sb.append(medal).append(" §f").append(name).append(" §7— §a").append(entry.getValue()).append(" pts\n");
            pos++;
        }
        AnnouncementSystem.announce(server, players, sb.toString());

        if (!ranking.isEmpty()) {
            ServerPlayerEntity winner = server.getPlayerManager().getPlayer(ranking.get(0).getKey());
            if (winner != null)
                AnnouncementSystem.announceTitle(server, players,
                    "§6¡" + winner.getName().getString() + " ganó!",
                    "§e" + ranking.get(0).getValue() + " puntos totales");
        }

        List<UUID> orderedForReward = new ArrayList<>(finishOrder);
        for (Map.Entry<UUID, Integer> e : ranking) {
            if (!orderedForReward.contains(e.getKey())) orderedForReward.add(e.getKey());
        }
        rewardSystem.giveRewards(server, orderedForReward, getDisplayName());
        rewardSystem.giveParticipationRewards(server, players, orderedForReward, getDisplayName());
    }

    @Override
    protected String getBossBarTitle(MinecraftServer server) {
        int timeLeft = (MAX_GAME_TICKS - gameTick) / 20;
        return "§e" + getDisplayName() + " §7| Tiempo: §f" + timeLeft + "s §7| Meta: §f" + finishOrder.size() + "/" + players.size();
    }

    @Override
    protected float getBossBarProgress(MinecraftServer server) {
        return 1f - (float) gameTick / MAX_GAME_TICKS;
    }

    private void sendRaceHUD(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        // BUG #15 FIX: jugadores que ya terminaron ven pantalla de espera, no HUD de carrera
        if (finishedPlayers.contains(uuid)) {
            int pos = finishOrder.indexOf(uuid) + 1;
            AnnouncementSystem.announceActionBarTo(player,
                "§a¡Terminaste en §6" + pos + ".º lugar§a! §7Esperando al resto...");
            return;
        }
        int posRace = racePositions.getOrDefault(uuid, 0);
        int pts     = scores.getOrDefault(uuid, 0);
        int cp      = checkpointProgress.getOrDefault(uuid, 0);
        // BUG #15 FIX: vuelta correcta — no superar totalLaps en el display
        int lap     = Math.min(lapsCompleted.getOrDefault(uuid, 0) + 1, totalLaps);
        boolean boost = boostSystem != null && boostSystem.isBoostActive(uuid);
        AnnouncementSystem.announceActionBarTo(player,
            String.format("§7Pos: §e%d  §7Pts: §a%d  §7CP: §f%d/%d  §7Vuelta: §f%d/%d%s",
                posRace, pts, cp, checkpoints != null ? checkpoints.size() : 0,
                lap, totalLaps, boost ? "  §6⚡BOOST" : ""));
    }

    private void updateRacePositions() {
        List<UUID> sorted = new ArrayList<>(players);
        sorted.removeAll(eliminatedPlayers);
        sorted.removeAll(finishedPlayers); // BUG #9 FIX: excluir terminados del ranking en curso
        sorted.sort((a, b) -> {
            int lapDiff = lapsCompleted.getOrDefault(b, 0) - lapsCompleted.getOrDefault(a, 0);
            if (lapDiff != 0) return lapDiff;
            int cpDiff  = checkpointProgress.getOrDefault(b, 0) - checkpointProgress.getOrDefault(a, 0);
            if (cpDiff != 0) return cpDiff;
            return scores.getOrDefault(b, 0) - scores.getOrDefault(a, 0);
        });
        // Jugadores que terminaron van primero en el mapa de posiciones
        int offset = finishOrder.size();
        for (int i = 0; i < sorted.size(); i++) racePositions.put(sorted.get(i), i + 1 + offset);
        for (int i = 0; i < finishOrder.size(); i++) racePositions.put(finishOrder.get(i), i + 1);
    }

    private boolean isNearPos(ServerPlayerEntity player, BlockPos pos, double radius) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return Math.sqrt(dx*dx + dz*dz) <= radius;
    }
}
