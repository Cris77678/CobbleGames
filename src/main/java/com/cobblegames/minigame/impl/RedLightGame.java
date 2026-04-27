package com.cobblegames.minigame.impl;

import com.cobblegames.music.MinigameSounds;
import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.arena.Arena;
import com.cobblegames.minigame.AbstractMinigame;
import com.cobblegames.minigame.EndReason;
import com.cobblegames.minigame.ValidationResult;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class RedLightGame extends AbstractMinigame {

    private static final int    GAME_DURATION_TICKS = 20 * 150;
    private static final double MOVEMENT_THRESHOLD  = 0.05;
    private static final int GREEN_MIN = 80, GREEN_MAX = 200;
    private static final int RED_MIN   = 40, RED_MAX   = 100;

    private enum TrafficLight { GREEN, RED }

    private TrafficLight currentLight = TrafficLight.GREEN;
    private int phaseDuration;
    private int phaseTimer;
    private final Map<UUID, Vec3d> frozenPositions = new HashMap<>();
    private int timeRemaining;
    private final Random random = new Random();
    private final List<UUID> finishOrder = new ArrayList<>();

    public RedLightGame(Arena arena) {
        super(arena);
        setMusic(MinigameSounds.MUSIC_RED_LIGHT, 20 * 150);
    }

    @Override public String getId()          { return "red_light"; }
    @Override public String getDisplayName() { return "Luz Roja Luz Verde"; }
    @Override public int getMinPlayers()     { return 2; }
    @Override public int getMaxPlayers()     { return 16; }

    @Override
    protected void validate(ValidationResult result) {
        super.validate(result);
        if (arena.getFinishLine() == null)
            result.addError("Falta 'finishLine' (posición de la meta).");
    }

    @Override
    protected void onGameStart(MinecraftServer server) {
        timeRemaining = GAME_DURATION_TICKS;
        currentLight  = TrafficLight.GREEN;
        phaseDuration = randomGreenDuration();
        phaseTimer    = phaseDuration;
        // BUG #11 FIX: registrar posiciones iniciales de todos los jugadores
        for (UUID uuid : players) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) frozenPositions.put(uuid, p.getPos());
        }
        AnnouncementSystem.announceTitle(server, players, "§a🟢 LUZ VERDE", "§7¡Muévete hacia la meta!");
    }

    @Override
    protected void onGameTick(MinecraftServer server) {
        timeRemaining--;
        phaseTimer--;
        ServerWorld world = server.getWorld(arena.getWorldKey());
        List<ServerPlayerEntity> active = getActivePlayers(server);

        if (phaseTimer <= 0) toggleLight(server, world, active);

        // BUG #7 FIX: comprobar llegada a meta ANTES de eliminar por movimiento
        // (un jugador que llega a la meta durante luz roja debería ganar, no ser eliminado)
        BlockPos finishLine = arena.getFinishLine();
        if (finishLine != null) {
            // Iterar sobre copia para evitar CME si playerReachedFinish llama onEnd
            for (ServerPlayerEntity player : new ArrayList<>(getActivePlayers(server))) {
                if (Math.abs(player.getX() - finishLine.getX()) <= 2
                    && Math.abs(player.getZ() - finishLine.getZ()) <= 2) {
                    playerReachedFinish(server, player);
                }
            }
        }

        // Comprobar movimiento durante luz roja
        if (currentLight == TrafficLight.RED) {
            List<ServerPlayerEntity> toEliminate = new ArrayList<>();
            for (ServerPlayerEntity player : getActivePlayers(server)) {
                // No eliminar a quienes ya llegaron a la meta
                if (finishOrder.contains(player.getUuid())) continue;
                if (hasMovedDuringRed(player)) toEliminate.add(player);
            }
            for (ServerPlayerEntity player : toEliminate) {
                if (world != null) world.spawnParticles(ParticleTypes.EXPLOSION,
                    player.getX(), player.getY(), player.getZ(), 5, 0.5, 0.5, 0.5, 0.1);
                eliminatePlayer(server, player, "§c¡Te moviste durante la Luz Roja! Eliminado.");
            }
        }

        if (gameTick % 15 == 0 && world != null && finishLine != null)
            spawnLightParticles(world);

        if (gameTick % 10 == 0) {
            String lightStr = currentLight == TrafficLight.GREEN ? "§a🟢 VERDE" : "§c🔴 ROJA";
            int nextSwitch  = phaseTimer / 20;
            for (ServerPlayerEntity player : getActivePlayers(server)) {
                AnnouncementSystem.announceActionBarTo(player,
                    lightStr + "  §7Cambia: §f" + nextSwitch + "s"
                    + "  §7Tiempo: §e" + (timeRemaining / 20) + "s"
                    + "  §7Activos: §f" + active.size());
            }
        }

        if (timeRemaining <= 0) onEnd(server, EndReason.TIME_OVER);
    }

    @Override
    protected void onGameEnd(MinecraftServer server, EndReason reason) {
        for (UUID uuid : players) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) p.removeStatusEffect(StatusEffects.GLOWING);
        }

        if (!finishOrder.isEmpty()) {
            ServerPlayerEntity winner = server.getPlayerManager().getPlayer(finishOrder.get(0));
            if (winner != null)
                AnnouncementSystem.announceTitle(server, players,
                    "§a¡" + winner.getName().getString() + " ganó!", "§7Llegó primero a la meta");
        } else {
            BlockPos finish = arena.getFinishLine();
            List<ServerPlayerEntity> survivors = getActivePlayers(server);
            if (finish != null && !survivors.isEmpty()) {
                ServerPlayerEntity closest = survivors.stream()
                    .min(Comparator.comparingDouble(p -> p.getBlockPos().getSquaredDistance(finish)))
                    .orElse(null);
                if (closest != null) {
                    finishOrder.add(closest.getUuid());
                    AnnouncementSystem.announceTitle(server, players,
                        "§e¡" + closest.getName().getString() + " ganó!",
                        "§7El más cercano a la meta");
                }
            } else {
                AnnouncementSystem.announce(server, players, "§7Todos fueron eliminados.");
            }
        }

        rewardSystem.giveRewards(server, finishOrder, getDisplayName());
        rewardSystem.giveParticipationRewards(server, players, finishOrder, getDisplayName());
    }

    @Override
    protected String getBossBarTitle(MinecraftServer server) {
        String lightStr = currentLight == TrafficLight.GREEN ? "§a🟢 VERDE" : "§c🔴 ROJA";
        return "§fLuz Roja/Verde §7| " + lightStr + " §7| Tiempo: §e" + (timeRemaining / 20) + "s"
            + " §7| Meta: §f" + finishOrder.size() + "/" + players.size();
    }

    @Override
    protected float getBossBarProgress(MinecraftServer server) {
        return (float) timeRemaining / GAME_DURATION_TICKS;
    }

    private void toggleLight(MinecraftServer server, ServerWorld world, List<ServerPlayerEntity> active) {
        if (currentLight == TrafficLight.GREEN) {
            currentLight  = TrafficLight.RED;
            phaseDuration = randomRedDuration();
            phaseTimer    = phaseDuration;
            frozenPositions.clear();
            for (ServerPlayerEntity player : active) {
                frozenPositions.put(player.getUuid(), player.getPos());
                player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.GLOWING, phaseDuration, 0, false, false, false));
            }
            AnnouncementSystem.announceTitle(server, players, "§c🔴 LUZ ROJA", "§c¡NO TE MUEVAS!");
            if (getBossBar() != null) getBossBar().setColor(BossBar.Color.RED);
        } else {
            currentLight  = TrafficLight.GREEN;
            phaseDuration = randomGreenDuration();
            phaseTimer    = phaseDuration;
            frozenPositions.clear();
            for (ServerPlayerEntity player : active) player.removeStatusEffect(StatusEffects.GLOWING);
            AnnouncementSystem.announceTitle(server, players, "§a🟢 LUZ VERDE", "§7¡Muévete!");
            if (getBossBar() != null) getBossBar().setColor(BossBar.Color.GREEN);
        }
    }

    private boolean hasMovedDuringRed(ServerPlayerEntity player) {
        Vec3d frozen = frozenPositions.get(player.getUuid());
        if (frozen == null) return false;
        Vec3d cur = player.getPos();
        double dx = cur.x - frozen.x, dy = cur.y - frozen.y, dz = cur.z - frozen.z;
        return Math.sqrt(dx*dx + dy*dy + dz*dz) > MOVEMENT_THRESHOLD;
    }

    private void playerReachedFinish(MinecraftServer server, ServerPlayerEntity player) {
        if (finishOrder.contains(player.getUuid())) return;
        finishOrder.add(player.getUuid());
        int pos = finishOrder.size();
        String medal = switch (pos) {
            case 1 -> "§6🥇 1.º"; case 2 -> "§7🥈 2.º"; case 3 -> "§c🥉 3.º";
            default -> "§7" + pos + ".º";
        };
        AnnouncementSystem.announce(server, players, medal + " §e" + player.getName().getString() + " §7llegó a la meta!");
        AnnouncementSystem.announceTitleTo(player, medal + " ¡Llegaste!", "§e¡Bien hecho!");

        // BUG #1 FIX: condición correcta — terminar cuando todos los activos llegaron
        List<ServerPlayerEntity> stillActive = getActivePlayers(server);
        boolean allFinishedOrEliminated = stillActive.stream()
            .allMatch(p -> finishOrder.contains(p.getUuid()));
        if (allFinishedOrEliminated && !stillActive.isEmpty())
            onEnd(server, EndReason.WINNER_FOUND);
    }

    private void spawnLightParticles(ServerWorld world) {
        BlockPos finish = arena.getFinishLine();
        var type = currentLight == TrafficLight.GREEN ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.FLAME;
        for (int i = -2; i <= 2; i++)
            world.spawnParticles(type, finish.getX() + i, finish.getY() + 1, finish.getZ(), 1, 0, 0.5, 0, 0.05);
    }

    private int randomGreenDuration() { return GREEN_MIN + random.nextInt(GREEN_MAX - GREEN_MIN); }
    private int randomRedDuration()   { return RED_MIN   + random.nextInt(RED_MAX   - RED_MIN); }
}
