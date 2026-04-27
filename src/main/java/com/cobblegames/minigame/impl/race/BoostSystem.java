package com.cobblegames.minigame.impl.race;

import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.config.CobbleGamesSettings;
import com.cobblegames.core.CobbleGames;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Sistema de hologramas/boosts para las carreras Pokémon.
 *
 * Cada boost es un punto en el mundo con un radio de activación.
 * Al pasar por él, el jugador recibe un efecto de velocidad temporal
 * y puntos extra. Los boosts tienen cooldown por jugador para evitar abuso.
 *
 * Los boosts pueden ser:
 *  - STATIC: siempre visibles durante la carrera
 *  - RANDOM: posiciones aleatorias de la lista de la arena
 *  - DYNAMIC: aparecen y desaparecen en intervalos
 */
public class BoostSystem {

    public enum BoostMode { STATIC, RANDOM, DYNAMIC }

    private static final int PARTICLE_INTERVAL = 4; // ticks entre partículas
    private static final double ACTIVATION_RADIUS = 2.5;
    private static final int COOLDOWN_TICKS = 100; // 5 segundos

    private final List<BlockPos> allBoostPositions;
    private final Set<BlockPos> activeBoosts = new HashSet<>();

    // uuid → tick en el que expira el cooldown
    private final Map<UUID, Map<BlockPos, Long>> playerBoostCooldowns = new HashMap<>();

    // uuid → ticks restantes de boost activo
    private final Map<UUID, Integer> activeBoostTicks = new HashMap<>();

    private final BoostMode mode;
    private final CobbleGamesSettings settings;
    private int tickCounter = 0;

    // Para DYNAMIC: cada cuántos ticks rotan los boosts activos
    private static final int DYNAMIC_CYCLE_TICKS = 200; // 10 segundos

    public BoostSystem(List<BlockPos> boostPositions, BoostMode mode, CobbleGamesSettings settings) {
        this.allBoostPositions = new ArrayList<>(boostPositions);
        this.mode = mode;
        this.settings = settings;
        initializeBoosts();
    }

    private void initializeBoosts() {
        switch (mode) {
            case STATIC -> activeBoosts.addAll(allBoostPositions);
            case RANDOM -> selectRandomBoosts();
            case DYNAMIC -> selectRandomBoosts(); // se rotarán con el tiempo
        }
    }

    private void selectRandomBoosts() {
        activeBoosts.clear();
        if (allBoostPositions.isEmpty()) return;
        List<BlockPos> shuffled = new ArrayList<>(allBoostPositions);
        Collections.shuffle(shuffled);
        int count = Math.max(1, shuffled.size() / 2);
        activeBoosts.addAll(shuffled.subList(0, count));
    }

    /**
     * Debe llamarse cada tick desde RaceGame.
     * Maneja partículas, detección de jugadores y ciclos dinámicos.
     */
    public void tick(MinecraftServer server, ServerWorld world, List<ServerPlayerEntity> players,
                     Map<UUID, Integer> scoreMap) {
        tickCounter++;

        // Ciclo dinámico: rotar boosts cada DYNAMIC_CYCLE_TICKS
        if (mode == BoostMode.DYNAMIC && tickCounter % DYNAMIC_CYCLE_TICKS == 0) {
            selectRandomBoosts();
        }

        // Partículas visuales en cada boost activo
        if (tickCounter % PARTICLE_INTERVAL == 0) {
            for (BlockPos pos : activeBoosts) {
                spawnBoostParticles(world, pos);
            }
        }

        // Verificar si algún jugador pasa por un boost
        for (ServerPlayerEntity player : players) {
            BlockPos playerPos = player.getBlockPos();
            for (BlockPos boostPos : activeBoosts) {
                if (isInRange(playerPos, boostPos) && !isOnCooldown(player.getUuid(), boostPos)) {
                    activateBoost(server, world, player, boostPos, scoreMap);
                }
            }
        }

        // Decrementar ticks de boost activo
        playerBoostTicks(server, players);
    }

    private void activateBoost(MinecraftServer server, ServerWorld world,
                                ServerPlayerEntity player, BlockPos boostPos,
                                Map<UUID, Integer> scoreMap) {
        // Aplicar efecto de velocidad
        int boostTicks = settings.boostDurationSeconds * 20;
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.SPEED, boostTicks, 2, false, true, true));

        // Efecto de salto leve para sensación extra
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.JUMP_BOOST, boostTicks, 1, false, false, false));

        // Registrar boost activo
        activeBoostTicks.put(player.getUuid(), boostTicks);

        // Puntos extra
        scoreMap.merge(player.getUuid(), settings.boostPoints, Integer::sum);

        // Cooldown: este boost no puede activarse de nuevo por COOLDOWN_TICKS
        playerBoostCooldowns
            .computeIfAbsent(player.getUuid(), k -> new HashMap<>())
            .put(boostPos, (long)(server.getTicks() + COOLDOWN_TICKS));

        // Efectos visuales de activación
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
            player.getX(), player.getY() + 1, player.getZ(),
            15, 0.3, 0.5, 0.3, 0.1);

        // Notificar al jugador
        AnnouncementSystem.announceActionBarTo(player,
            "§6⚡ §eBOOST ACTIVADO §6+§a" + settings.boostPoints + " pts");
    }

    private void playerBoostTicks(MinecraftServer server, List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            UUID uuid = player.getUuid();
            int remaining = activeBoostTicks.getOrDefault(uuid, 0);
            if (remaining > 0) {
                activeBoostTicks.put(uuid, remaining - 1);
                // Action bar con tiempo restante
                if (remaining % 20 == 0 && remaining > 0) {
                    int secs = remaining / 20;
                    AnnouncementSystem.announceActionBarTo(player,
                        "§6⚡ Boost: §e" + secs + "s");
                }
            }
        }
    }

    public boolean isBoostActive(UUID playerId) {
        return activeBoostTicks.getOrDefault(playerId, 0) > 0;
    }

    private boolean isOnCooldown(UUID playerId, BlockPos boostPos) {
        Map<BlockPos, Long> cooldowns = playerBoostCooldowns.get(playerId);
        if (cooldowns == null) return false;
        Long expiry = cooldowns.get(boostPos);
        if (expiry == null) return false;
        return CobbleGames.getServer().getTicks() < expiry;
    }

    private boolean isInRange(BlockPos player, BlockPos boost) {
        double dx = player.getX() - boost.getX();
        double dz = player.getZ() - boost.getZ();
        double dy = player.getY() - boost.getY();
        return Math.sqrt(dx*dx + dy*dy + dz*dz) <= ACTIVATION_RADIUS;
    }

    private void spawnBoostParticles(ServerWorld world, BlockPos pos) {
        // Anillo de partículas giratorio (efecto holograma)
        for (int i = 0; i < 8; i++) {
            double angle = (tickCounter * 0.15 + i * (Math.PI / 4));
            double radius = 0.8;
            double px = pos.getX() + 0.5 + radius * Math.cos(angle);
            double pz = pos.getZ() + 0.5 + radius * Math.sin(angle);
            double py = pos.getY() + 1.0 + Math.sin(tickCounter * 0.1 + i) * 0.2;
            world.spawnParticles(ParticleTypes.END_ROD, px, py, pz, 1, 0, 0, 0, 0);
        }
        // Pilar central
        world.spawnParticles(ParticleTypes.ENCHANT,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            3, 0.1, 0.5, 0.1, 0.05);
    }

    public void reset() {
        activeBoosts.clear();
        playerBoostCooldowns.clear();
        activeBoostTicks.clear();
        tickCounter = 0;
        initializeBoosts();
    }
}
