package com.cobblegames.minigame.impl;

import com.cobblegames.music.MinigameSounds;
import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.arena.Arena;
import com.cobblegames.minigame.AbstractMinigame;
import com.cobblegames.minigame.EndReason;
import com.cobblegames.minigame.ValidationResult;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class FreezeTagGame extends AbstractMinigame {

    private static final int GAME_DURATION_TICKS = 20 * 120;   // 2 min
    private static final double FREEZE_RADIUS   = 1.8;
    private static final double UNFREEZE_RADIUS = 2.0;
    private static final int FREEZE_COOLDOWN    = 60;

    private final Set<UUID> hunters          = new HashSet<>();
    private final Set<UUID> frozenPlayers    = new HashSet<>();
    private final Map<UUID, Integer> freezeCooldowns = new HashMap<>();
    private final Map<UUID, Vec3d> frozenAnchors     = new HashMap<>();
    private int timeRemaining;

    // BUG #8 FIX: limpiar estado de congelado/cazador al desconectarse
    @Override
    public void onPlayerLeave(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        frozenPlayers.remove(uuid);
        frozenAnchors.remove(uuid);
        hunters.remove(uuid);
        freezeCooldowns.remove(uuid);
        super.onPlayerLeave(player);
    }


    public FreezeTagGame(Arena arena) {
        super(arena);
        setMusic(MinigameSounds.MUSIC_FREEZE_TAG, 20 * 120);
    }

    @Override public String getId()          { return "freeze_tag"; }
    @Override public String getDisplayName() { return "Freeze Tag"; }
    @Override public int getMinPlayers()     { return 3; }
    @Override public int getMaxPlayers()     { return 12; }

    // ─── Validación ──────────────────────────────────────────────────────────

    @Override
    protected void validate(ValidationResult result) {
        super.validate(result);
        // Con 3+ jugadores siempre hay cazador válido, pero al menos necesita spawnPoints
        // (ya validados en super). Freeze Tag no requiere config especial de arena.
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onGameStart(MinecraftServer server) {
        timeRemaining = GAME_DURATION_TICKS;

        // Asignar cazadores (aprox 25% de los jugadores, mín 1)
        List<UUID> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        int hunterCount = Math.max(1, players.size() / 4);
        for (int i = 0; i < hunterCount; i++) hunters.add(shuffled.get(i));

        for (UUID uuid : players) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;
            if (hunters.contains(uuid)) {
                AnnouncementSystem.announceTitleTo(player, "§c¡CAZADOR!", "§7Congela a todos");
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, -1, 1, false, false, false));
            } else {
                AnnouncementSystem.announceTitleTo(player, "§b¡LIBRE!", "§7Ayuda a liberar congelados");
            }
        }

        AnnouncementSystem.announce(server, players,
            "§7Cazadores: §c" + hunterCount + "  §7Libres: §b" + (players.size() - hunterCount));
    }

    @Override
    protected void onGameTick(MinecraftServer server) {
        timeRemaining--;
        ServerWorld world = server.getWorld(arena.getWorldKey());
        List<ServerPlayerEntity> active = getActivePlayers(server);

        // Reducir cooldowns de cazadores
        hunters.forEach(uuid -> freezeCooldowns.merge(uuid, -1, (a, b) -> Math.max(0, a + b)));

        for (ServerPlayerEntity player : active) {
            UUID uuid = player.getUuid();
            if (hunters.contains(uuid)) {
                tickHunter(server, world, player, active);
            } else if (!frozenPlayers.contains(uuid)) {
                tickFreePlayer(server, world, player, active);
            }
            if (frozenPlayers.contains(uuid)) maintainFreeze(player, world);
        }

        // Comprobar victoria de cazadores: todos los no-cazadores congelados
        List<UUID> targets = active.stream()
            .map(ServerPlayerEntity::getUuid)
            .filter(uuid -> !hunters.contains(uuid))
            .toList();

        if (!targets.isEmpty() && frozenPlayers.containsAll(targets)) {
            onEnd(server, EndReason.WINNER_FOUND);
            return;
        }

        if (timeRemaining <= 0) onEnd(server, EndReason.TIME_OVER);
    }

    @Override
    protected void onGameEnd(MinecraftServer server, EndReason reason) {
        // Limpiar estados
        frozenAnchors.clear();
        for (UUID uuid : players) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                p.removeStatusEffect(StatusEffects.SLOWNESS);
                p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
                p.removeStatusEffect(StatusEffects.GLOWING);
                p.removeStatusEffect(StatusEffects.SPEED);
            }
        }

        if (reason == EndReason.WINNER_FOUND) {
            AnnouncementSystem.announceTitle(server, players, "§c¡Cazadores ganaron!", "§7Todos fueron congelados.");
        } else {
            long stillFree = players.stream()
                .filter(uuid -> !hunters.contains(uuid) && !frozenPlayers.contains(uuid))
                .count();
            AnnouncementSystem.announceTitle(server, players,
                "§b¡Se acabó el tiempo!", "§a" + stillFree + " jugadores sobrevivieron.");
        }

        // Recompensas: ganadores son los cazadores si WINNER_FOUND, si TIME_OVER los libres sobrevivientes
        List<UUID> winners;
        if (reason == EndReason.WINNER_FOUND) {
            winners = new ArrayList<>(hunters);
        } else {
            winners = players.stream()
                .filter(uuid -> !hunters.contains(uuid) && !frozenPlayers.contains(uuid))
                .collect(java.util.stream.Collectors.toList());
        }
        rewardSystem.giveRewards(server, winners, getDisplayName());
        rewardSystem.giveParticipationRewards(server, players, winners, getDisplayName());
    }

    // ─── BossBar ─────────────────────────────────────────────────────────────

    @Override
    protected String getBossBarTitle(MinecraftServer server) {
        int secs = timeRemaining / 20;
        // BUG #8 FIX: contar solo congelados que siguen conectados
        long frozen = frozenPlayers.stream().filter(players::contains).count();
        long total  = players.stream().filter(uuid -> !hunters.contains(uuid)).count();
        return "§cFreeze Tag §7| §bCongelados: §f" + frozen + "/" + total + " §7| Tiempo: §e" + secs + "s";
    }

    @Override
    protected float getBossBarProgress(MinecraftServer server) {
        return (float) timeRemaining / GAME_DURATION_TICKS;
    }

    // ─── Mecánicas ────────────────────────────────────────────────────────────

    private void tickHunter(MinecraftServer server, ServerWorld world,
                             ServerPlayerEntity hunter, List<ServerPlayerEntity> active) {
        if (freezeCooldowns.getOrDefault(hunter.getUuid(), 0) > 0) return;
        for (ServerPlayerEntity target : active) {
            // BUG #3 FIX: doble guard - nunca congelar a cazadores ni ya congelados
            if (hunters.contains(target.getUuid())) continue;
            if (frozenPlayers.contains(target.getUuid())) continue;
            if (hunter.distanceTo(target) <= FREEZE_RADIUS) {
                freezePlayer(server, world, target, hunter);
                freezeCooldowns.put(hunter.getUuid(), FREEZE_COOLDOWN);
                break;
            }
        }
    }

    private void tickFreePlayer(MinecraftServer server, ServerWorld world,
                                 ServerPlayerEntity freePlayer, List<ServerPlayerEntity> active) {
        for (ServerPlayerEntity frozen : active) {
            UUID frozenUuid = frozen.getUuid();
            // BUG #3 FIX: solo descongelar no-cazadores que estén realmente en frozenPlayers
            if (!frozenPlayers.contains(frozenUuid)) continue;
            if (hunters.contains(frozenUuid)) continue; // cazadores no pueden ser congelados
            if (freePlayer.distanceTo(frozen) <= UNFREEZE_RADIUS) {
                unfreezePlayer(server, world, frozen, freePlayer);
                break;
            }
        }
    }

    private void freezePlayer(MinecraftServer server, ServerWorld world,
                               ServerPlayerEntity target, ServerPlayerEntity hunter) {
        frozenPlayers.add(target.getUuid());
        frozenAnchors.put(target.getUuid(), target.getPos());
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS,    -1, 127, false, false, false));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, -1, 127, false, false, false));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING,     -1, 0, false, false, false));
        if (world != null) {
            world.spawnParticles(ParticleTypes.SNOWFLAKE,
                target.getX(), target.getY() + 1, target.getZ(), 20, 0.5, 0.5, 0.5, 0.05);
        }
        AnnouncementSystem.announce(server, players,
            "§b❄ " + target.getName().getString() + " §7fue congelado por §c" + hunter.getName().getString());
        AnnouncementSystem.announceTitleTo(target, "§b❄ CONGELADO", "§7¡Alguien te libere!");
    }

    private void unfreezePlayer(MinecraftServer server, ServerWorld world,
                                 ServerPlayerEntity frozen, ServerPlayerEntity liberator) {
        frozenPlayers.remove(frozen.getUuid());
        frozenAnchors.remove(frozen.getUuid());
        frozen.removeStatusEffect(StatusEffects.SLOWNESS);
        frozen.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        frozen.removeStatusEffect(StatusEffects.GLOWING);
        if (world != null) {
            world.spawnParticles(ParticleTypes.HEART,
                frozen.getX(), frozen.getY() + 1, frozen.getZ(), 8, 0.5, 0.5, 0.5, 0.05);
        }
        AnnouncementSystem.announce(server, players,
            "§a✓ " + frozen.getName().getString() + " §7fue liberado por §a" + liberator.getName().getString());
    }

    private void maintainFreeze(ServerPlayerEntity player, ServerWorld world) {
        Vec3d anchor = frozenAnchors.get(player.getUuid());
        if (anchor != null && player.getPos().squaredDistanceTo(anchor) > 0.25) {
            player.requestTeleport(anchor.x, anchor.y, anchor.z);
        }
        if (gameTick % 10 == 0 && world != null) {
            world.spawnParticles(ParticleTypes.ITEM_SNOWBALL,
                player.getX(), player.getY() + 0.5, player.getZ(), 3, 0.3, 0.3, 0.3, 0.01);
        }
    }
}
