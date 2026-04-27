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

import java.util.*;
import java.util.stream.Collectors;

public class KingOfHillGame extends AbstractMinigame {

    private static final int GAME_DURATION_TICKS = 20 * 180;
    private static final int POINTS_PER_SECOND   = 10; // BUG #6 FIX: subido para que la división sea más justa
    private static final int PARTICLE_INTERVAL   = 10;

    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private int timeRemaining;

    public KingOfHillGame(Arena arena) {
        super(arena);
        setMusic(MinigameSounds.MUSIC_KING_OF_HILL, 20 * 180);
    }

    @Override public String getId()          { return "king_of_hill"; }
    @Override public String getDisplayName() { return "King of the Hill"; }
    @Override public int getMinPlayers()     { return 2; }
    @Override public int getMaxPlayers()     { return 10; }

    @Override
    protected void validate(ValidationResult result) {
        super.validate(result);
        if (arena.getHillCenter() == null)
            result.addError("Falta 'hillCenter' (posición del centro de la colina).");
    }

    @Override
    protected void onGameStart(MinecraftServer server) {
        timeRemaining = GAME_DURATION_TICKS;
        players.forEach(uuid -> scores.put(uuid, 0));
        AnnouncementSystem.announce(server, players,
            "§e¡Acumula puntos en §6la colina§e! Duración: §a3 min §7| Radio: §a" + (int)arena.getHillRadius() + " bloques");
    }

    @Override
    protected void onGameTick(MinecraftServer server) {
        timeRemaining--;
        ServerWorld world = server.getWorld(arena.getWorldKey());
        List<ServerPlayerEntity> active = getActivePlayers(server);

        if (gameTick % PARTICLE_INTERVAL == 0 && world != null && arena.getHillCenter() != null)
            spawnHillParticles(world);

        List<ServerPlayerEntity> inHill = active.stream()
            .filter(p -> arena.isInHillZone(p.getBlockPos()))
            .collect(Collectors.toList());

        // BUG #6 FIX: puntos fijos por jugador en la colina — no dividir, sino usar escala inversa
        // Solo jugador → puntos completos. Varios → puntos proporcionales pero siempre positivos.
        if (gameTick % 20 == 0 && !inHill.isEmpty()) {
            // 1 jugador = 10 pts, 2 = 7 pts c/u, 3 = 5 pts, 4+ = 3 pts
            int ptsEach = switch (inHill.size()) {
                case 1  -> POINTS_PER_SECOND;
                case 2  -> 7;
                case 3  -> 5;
                default -> 3;
            };
            for (ServerPlayerEntity p : inHill) {
                scores.merge(p.getUuid(), ptsEach, Integer::sum);
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 25, 0, false, false, false));
            }
        }

        if (gameTick % 20 == 0 && getBossBar() != null)
            getBossBar().setColor(inHill.isEmpty() ? BossBar.Color.WHITE : BossBar.Color.YELLOW);

        if (timeRemaining <= 0) onEnd(server, EndReason.TIME_OVER);
    }

    @Override
    protected void onGameEnd(MinecraftServer server, EndReason reason) {
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(scores.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        StringBuilder sb = new StringBuilder("§6=== King of the Hill — Resultados ===\n");
        int pos = 1;
        for (Map.Entry<UUID, Integer> entry : ranking) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
            String name = p != null ? p.getName().getString() : "?";
            String medal = switch (pos) { case 1 -> "§6🥇"; case 2 -> "§7🥈"; case 3 -> "§c🥉"; default -> "§7" + pos + "."; };
            sb.append(medal).append(" §f").append(name).append("  §a").append(entry.getValue()).append(" pts\n");
            pos++;
        }
        AnnouncementSystem.announce(server, players, sb.toString());

        // BUG #14 FIX: solo anunciar ganador si realmente acumuló puntos
        if (!ranking.isEmpty() && ranking.get(0).getValue() > 0) {
            ServerPlayerEntity winner = server.getPlayerManager().getPlayer(ranking.get(0).getKey());
            if (winner != null)
                AnnouncementSystem.announceTitle(server, players,
                    "§6¡" + winner.getName().getString() + " es el REY!",
                    "§e" + ranking.get(0).getValue() + " puntos");
        } else {
            AnnouncementSystem.announceTitle(server, players, "§7Empate a 0", "§7Nadie pisó la colina.");
        }

        // BUG #14 FIX: no dar recompensa de 1.º si todos tienen 0 puntos
        List<UUID> orderedUuids = ranking.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        boolean anyoneScored = ranking.stream().anyMatch(e -> e.getValue() > 0);
        if (anyoneScored) {
            rewardSystem.giveRewards(server, orderedUuids, getDisplayName());
        }
        rewardSystem.giveParticipationRewards(server, players, anyoneScored ? orderedUuids : List.of(), getDisplayName());
    }

    @Override
    protected String getBossBarTitle(MinecraftServer server) {
        int secs = timeRemaining / 20;
        Optional<Map.Entry<UUID, Integer>> leader = scores.entrySet().stream()
            .max(Map.Entry.comparingByValue());
        String leaderStr = leader.map(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            return p != null ? p.getName().getString() + " §a" + e.getValue() + "pts" : "?";
        }).orElse("§7--");
        return "§6King of the Hill §7| §eLíder: §f" + leaderStr + " §7| §fTiempo: §e" + secs + "s";
    }

    @Override
    protected float getBossBarProgress(MinecraftServer server) {
        return (float) timeRemaining / GAME_DURATION_TICKS;
    }

    private void spawnHillParticles(ServerWorld world) {
        BlockPos center = arena.getHillCenter();
        double radius   = arena.getHillRadius();
        int points      = (int)(radius * 4);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double px = center.getX() + 0.5 + radius * Math.cos(angle);
            double pz = center.getZ() + 0.5 + radius * Math.sin(angle);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, px, center.getY() + 0.1, pz, 1, 0, 0.05, 0, 0);
        }
    }
}
