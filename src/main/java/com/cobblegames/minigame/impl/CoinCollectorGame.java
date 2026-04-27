package com.cobblegames.minigame.impl;

import com.cobblegames.music.MinigameSounds;
import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.arena.Arena;
import com.cobblegames.minigame.AbstractMinigame;
import com.cobblegames.minigame.EndReason;
import com.cobblegames.minigame.ValidationResult;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class CoinCollectorGame extends AbstractMinigame {

    private static final int GAME_DURATION_TICKS = 20 * 120;      // 2 min
    private static final int COIN_PICKUP_RADIUS   = 2;
    private static final int RESPAWN_INTERVAL     = 60;
    private static final double SPECIAL_COIN_CHANCE = 0.1;
    private static final int MAX_RANDOM_COINS     = 20;

    private final Map<UUID, Integer> coinsCollected = new LinkedHashMap<>();
    private final Map<BlockPos, Boolean> activeCoins = new LinkedHashMap<>();
    private int timeRemaining;
    private final Random random = new Random();

    public CoinCollectorGame(Arena arena) {
        super(arena);
        setMusic(MinigameSounds.MUSIC_COIN_COLLECTOR, 20 * 120); // 2 min loop
    }

    @Override public String getId() { return "coin_collector"; }
    @Override public String getDisplayName() { return "Coin Collector"; }
    @Override public int getMinPlayers() { return 2; }
    @Override public int getMaxPlayers() { return 8; }

    // ─── Validación ──────────────────────────────────────────────────────────

    @Override
    protected void validate(ValidationResult result) {
        super.validate(result);
        // Requiere o bien coinSpawns o bien boundsMin/Max para generar monedas aleatorias
        if (arena.getCoinSpawnPoints().isEmpty()
            && (arena.getBoundsMin() == null || arena.getBoundsMax() == null)) {
            result.addError("Necesita 'coinSpawns' O 'boundsMin'+'boundsMax' para generar monedas.");
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onGameStart(MinecraftServer server) {
        timeRemaining = GAME_DURATION_TICKS;
        players.forEach(uuid -> coinsCollected.put(uuid, 0));
        spawnAllCoins();
        AnnouncementSystem.announce(server, players,
            "§e¡Recolecta las §6monedas§e! Duración: §a2 min §7| §6Monedas especiales§7: §ax5");
    }

    @Override
    protected void onGameTick(MinecraftServer server) {
        timeRemaining--;
        ServerWorld world = server.getWorld(arena.getWorldKey());
        List<ServerPlayerEntity> active = getActivePlayers(server);

        active.forEach(player -> checkCoinPickup(server, world, player));

        if (gameTick % RESPAWN_INTERVAL == 0) respawnMissingCoins();

        if (gameTick % 20 == 0 && world != null) spawnCoinParticles(world);

        if (timeRemaining <= 0) onEnd(server, EndReason.TIME_OVER);
    }

    @Override
    protected void onGameEnd(MinecraftServer server, EndReason reason) {
        activeCoins.clear();

        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(coinsCollected.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        // Resultados en chat
        StringBuilder sb = new StringBuilder("§6=== Coin Collector — Resultados ===\n");
        int pos = 1;
        for (Map.Entry<UUID, Integer> entry : ranking) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
            String name = p != null ? p.getName().getString() : "?";
            sb.append(posEmoji(pos)).append(" §f").append(name)
              .append("  §e").append(entry.getValue()).append(" monedas\n");
            pos++;
        }
        AnnouncementSystem.announce(server, players, sb.toString());

        if (!ranking.isEmpty()) {
            ServerPlayerEntity winner = server.getPlayerManager().getPlayer(ranking.get(0).getKey());
            if (winner != null) {
                AnnouncementSystem.announceTitle(server, players,
                    "§6¡" + winner.getName().getString() + " ganó!",
                    "§e" + ranking.get(0).getValue() + " monedas recolectadas");
            }
        }

        // Recompensas impactor
        List<UUID> orderedUuids = ranking.stream().map(Map.Entry::getKey).toList();
        rewardSystem.giveRewards(server, orderedUuids, getDisplayName());
        rewardSystem.giveParticipationRewards(server, players, orderedUuids, getDisplayName());
    }

    // ─── BossBar ─────────────────────────────────────────────────────────────

    @Override
    protected String getBossBarTitle(MinecraftServer server) {
        int secs = timeRemaining / 20;
        // Líder actual
        Optional<Map.Entry<UUID, Integer>> leader = coinsCollected.entrySet().stream()
            .max(Map.Entry.comparingByValue());
        String leaderStr = leader.map(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            return p != null ? "§6" + p.getName().getString() + " §e" + e.getValue() + "🪙" : "";
        }).orElse("§7--");
        return "§eCoin Collector §7| §fTiempo: §e" + secs + "s §7| Líder: " + leaderStr;
    }

    @Override
    protected float getBossBarProgress(MinecraftServer server) {
        return (float) timeRemaining / GAME_DURATION_TICKS;
    }

    // ─── Monedas ──────────────────────────────────────────────────────────────

    private void checkCoinPickup(MinecraftServer server, ServerWorld world, ServerPlayerEntity player) {
        BlockPos playerPos = player.getBlockPos();
        Iterator<Map.Entry<BlockPos, Boolean>> it = activeCoins.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Boolean> entry = it.next();
            BlockPos coinPos = entry.getKey();
            boolean special  = entry.getValue();
            if (isNear(playerPos, coinPos, COIN_PICKUP_RADIUS)) {
                int value = special ? 5 : 1;
                coinsCollected.merge(player.getUuid(), value, Integer::sum);
                it.remove();
                AnnouncementSystem.announceActionBarTo(player,
                    special ? "§6★ ¡Moneda especial! §a+5"
                            : "§e● Moneda §a+1 §7(Total: " + coinsCollected.get(player.getUuid()) + ")");
                if (world != null) {
                    world.spawnParticles(special ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.HAPPY_VILLAGER,
                        player.getX(), player.getY() + 0.5, player.getZ(), 5, 0.3, 0.3, 0.3, 0.1);
                }
            }
        }
    }

    private void spawnAllCoins() {
        for (BlockPos pos : arena.getCoinSpawnPoints()) {
            activeCoins.put(pos, random.nextDouble() < SPECIAL_COIN_CHANCE);
        }
        if (activeCoins.isEmpty()) generateRandomCoins(MAX_RANDOM_COINS);
    }

    private void generateRandomCoins(int count) {
        BlockPos min = arena.getBoundsMin();
        BlockPos max = arena.getBoundsMax();
        if (min == null || max == null) return;
        int xRange = Math.abs(max.getX() - min.getX()) + 1;
        int zRange = Math.abs(max.getZ() - min.getZ()) + 1;
        // BUG #12 FIX: colocar monedas sobre el bloque sólido más alto, no en boundsMin.Y fijo
        net.minecraft.server.world.ServerWorld world =
            com.cobblegames.core.CobbleGames.getServer().getWorld(arena.getWorldKey());
        for (int i = 0; i < count; i++) {
            int x = min.getX() + random.nextInt(xRange);
            int z = min.getZ() + random.nextInt(zRange);
            int y = min.getY();
            if (world != null) {
                // Buscar el primer bloque sólido desde max.Y hacia abajo
                for (int ty = Math.min(max.getY(), min.getY() + 64); ty >= min.getY(); ty--) {
                    BlockPos check = new BlockPos(x, ty, z);
                    if (!world.getBlockState(check).isAir()) { y = ty + 1; break; }
                }
            }
            activeCoins.put(new BlockPos(x, y, z), random.nextDouble() < SPECIAL_COIN_CHANCE);
        }
    }

    private void respawnMissingCoins() {
        List<BlockPos> spawns = arena.getCoinSpawnPoints();
        if (!spawns.isEmpty()) {
            for (BlockPos spawn : spawns) {
                if (!activeCoins.containsKey(spawn)) {
                    activeCoins.put(spawn, random.nextDouble() < SPECIAL_COIN_CHANCE);
                }
            }
        } else if (activeCoins.size() < MAX_RANDOM_COINS) {
            generateRandomCoins(MAX_RANDOM_COINS - activeCoins.size());
        }
    }

    private void spawnCoinParticles(ServerWorld world) {
        for (Map.Entry<BlockPos, Boolean> entry : activeCoins.entrySet()) {
            BlockPos pos = entry.getKey();
            world.spawnParticles(entry.getValue() ? ParticleTypes.TOTEM_OF_UNDYING : ParticleTypes.ENCHANT,
                pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                2, 0.2, 0.2, 0.2, 0.02);
        }
    }

    // ─── Utilidades ───────────────────────────────────────────────────────────

    private boolean isNear(BlockPos a, BlockPos b, double radius) {
        return Math.abs(a.getX() - b.getX()) <= radius
            && Math.abs(a.getZ() - b.getZ()) <= radius;
    }

    private String posEmoji(int pos) {
        return switch (pos) {
            case 1 -> "§61.º §6🥇";
            case 2 -> "§72.º 🥈";
            case 3 -> "§c3.º 🥉";
            default -> "§7" + pos + ".º";
        };
    }
}
