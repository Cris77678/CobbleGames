package com.cobblegames.minigame.impl;

import com.cobblegames.music.MinigameSounds;
import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.arena.Arena;
import com.cobblegames.minigame.AbstractMinigame;
import com.cobblegames.minigame.EndReason;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.*;

public class HotPotatoGame extends AbstractMinigame {

    private static final double PASS_RADIUS        = 2.5;
    private static final int    MIN_TIMER_TICKS    = 200;  // 10s
    private static final int    MAX_TIMER_TICKS    = 400;  // 20s
    private static final int    PASS_COOLDOWN_TICKS = 40;  // 2s anti-rebote

    private UUID hotPotatoHolder;
    private int  hotPotatoTimer;
    private UUID lastReceiver   = null;
    private int  receiverCooldown = 0;
    private final Random random = new Random();

    public HotPotatoGame(Arena arena) {
        super(arena);
        setMusic(MinigameSounds.MUSIC_HOT_POTATO, 20 * 60); // 1 min loop (pista corta y tensa)
    }

    @Override public String getId()          { return "hot_potato"; }
    @Override public String getDisplayName() { return "Hot Potato"; }
    @Override public int getMinPlayers()     { return 3; }
    @Override public int getMaxPlayers()     { return 12; }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onGameStart(MinecraftServer server) {
        List<UUID> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        hotPotatoHolder = shuffled.get(0);
        hotPotatoTimer  = randomTimer();

        ServerPlayerEntity holder = server.getPlayerManager().getPlayer(hotPotatoHolder);
        if (holder != null) {
            AnnouncementSystem.announceTitle(server, players,
                "§c" + holder.getName().getString() + " §etiene el §cElectrode§e!",
                "§7¡Pásalo antes de que explote!");
            applyHolderEffects(holder);
        }
    }

    @Override
    protected void onGameTick(MinecraftServer server) {
        List<ServerPlayerEntity> active = getActivePlayers(server);
        if (active.size() <= 1) {
            onEnd(server, active.isEmpty() ? EndReason.TIME_OVER : EndReason.WINNER_FOUND);
            return;
        }

        if (receiverCooldown > 0) receiverCooldown--;

        ServerPlayerEntity holder = getHolder(server);
        if (holder == null) {
            reassignRandom(server, active);
            return;
        }

        // Partículas del electrode
        if (gameTick % 4 == 0) spawnElectrodeParticles(server, holder);

        // Pasar la papa si alguien está cerca (con cooldown anti-rebote)
        if (receiverCooldown <= 0) {
            for (ServerPlayerEntity other : active) {
                if (other.getUuid().equals(hotPotatoHolder)) continue;
                if (holder.distanceTo(other) <= PASS_RADIUS) {
                    passPotatoTo(server, other);
                    return;
                }
            }
        }

        hotPotatoTimer--;

        // Advertencia al portador en los últimos 5 segundos
        int secsLeft = hotPotatoTimer / 20;
        if (hotPotatoTimer % 20 == 0 && secsLeft <= 5 && secsLeft > 0) {
            AnnouncementSystem.announceTo(holder, "§c¡EXPLOTA EN " + secsLeft + "s!");
            holder.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§c⚡ " + secsLeft + "s")));
        }

        if (hotPotatoTimer <= 0) explode(server, holder, active);
    }

    @Override
    protected void onGameEnd(MinecraftServer server, EndReason reason) {
        // Limpiar efectos del portador actual
        ServerPlayerEntity holder = getHolder(server);
        if (holder != null) {
            holder.removeStatusEffect(StatusEffects.GLOWING);
            holder.removeStatusEffect(StatusEffects.SPEED);
        }

        List<ServerPlayerEntity> survivors = getActivePlayers(server);
        if (!survivors.isEmpty()) {
            ServerPlayerEntity winner = survivors.get(0);
            AnnouncementSystem.announceTitle(server, players,
                "§a¡" + winner.getName().getString() + " ganó!",
                "§7¡Sobrevivió al Electrode!");
            rewardSystem.giveRewards(server, List.of(winner.getUuid()), getDisplayName());
        } else {
            AnnouncementSystem.announce(server, players, "§7Nadie sobrevivió al Electrode.");
        }

        List<UUID> rewarded = survivors.isEmpty() ? List.of() : List.of(survivors.get(0).getUuid());
        rewardSystem.giveParticipationRewards(server, players, rewarded, getDisplayName());
    }

    // ─── BossBar ─────────────────────────────────────────────────────────────

    @Override
    protected String getBossBarTitle(MinecraftServer server) {
        ServerPlayerEntity holder = getHolder(server);
        String holderName = holder != null ? holder.getName().getString() : "?";
        int secsLeft = hotPotatoTimer / 20;
        int alive    = getActivePlayers(server).size();
        return "§cElectrode en §e" + holderName + " §7| §cExplota: §f" + secsLeft + "s §7| §fVivos: §e" + alive;
    }

    @Override
    protected float getBossBarProgress(MinecraftServer server) {
        // BUG #10 FIX: clampar entre 0 y 1
        return Math.max(0f, Math.min(1f, (float) hotPotatoTimer / MAX_TIMER_TICKS));
    }

    // ─── Mecánicas ────────────────────────────────────────────────────────────

    private void passPotatoTo(MinecraftServer server, ServerPlayerEntity receiver) {
        ServerPlayerEntity old = getHolder(server);
        if (old != null) {
            old.removeStatusEffect(StatusEffects.GLOWING);
            old.removeStatusEffect(StatusEffects.SPEED);
        }
        hotPotatoHolder  = receiver.getUuid();
        hotPotatoTimer   = randomTimer();
        lastReceiver     = receiver.getUuid();
        receiverCooldown = PASS_COOLDOWN_TICKS;

        applyHolderEffects(receiver);

        AnnouncementSystem.announce(server, players,
            "§e" + (old != null ? old.getName().getString() : "?")
            + " §7pasó el Electrode a §c" + receiver.getName().getString() + "§7!");

        // BUG #12 FIX: enviar sonido solo a jugadores en partida, no broadcast global
        com.cobblegames.announce.AnnouncementSystem.announceSound(
            server, players, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);

        // Color de bossbar cambia con el tiempo
        if (getBossBar() != null) {
            getBossBar().setColor(BossBar.Color.RED);
        }
    }

    private void explode(MinecraftServer server, ServerPlayerEntity holder, List<ServerPlayerEntity> active) {
        ServerWorld world = server.getWorld(arena.getWorldKey());
        if (world != null) {
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
                holder.getX(), holder.getY(), holder.getZ(), 1, 0, 0, 0, 0);
            // BUG #12 FIX: sonido de explosión solo para jugadores en partida
            com.cobblegames.announce.AnnouncementSystem.announceSound(
                server, players, SoundEvents.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        }
        AnnouncementSystem.announceTitle(server, players, "§c💥 BOOM!", "§e" + holder.getName().getString() + " §7fue eliminado");
        eliminatePlayer(server, holder, "§c¡El Electrode explotó! Fuiste eliminado.");

        // Reasignar a un sobreviviente aleatorio
        List<ServerPlayerEntity> survivors = getActivePlayers(server);
        if (survivors.size() > 1) {
            Collections.shuffle(survivors);
            ServerPlayerEntity next = survivors.get(0);
            hotPotatoHolder = next.getUuid();
            hotPotatoTimer  = randomTimer();
            applyHolderEffects(next);
            AnnouncementSystem.announce(server, players, "§cEl Electrode rebotó hacia §e" + next.getName().getString() + "§c!");
        }
    }

    private void applyHolderEffects(ServerPlayerEntity player) {
        // BUG #4 FIX: no aplicar efectos a jugadores eliminados/espectadores
        if (eliminatedPlayers.contains(player.getUuid())) return;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, -1, 0, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED,   -1, 0, false, false, false));
    }

    private void spawnElectrodeParticles(MinecraftServer server, ServerPlayerEntity holder) {
        ServerWorld world = server.getWorld(arena.getWorldKey());
        if (world == null) return;
        float intensity = 1f - (float) hotPotatoTimer / MAX_TIMER_TICKS;
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
            holder.getX(), holder.getY() + 1, holder.getZ(),
            3 + (int)(intensity * 10), 0.3, 0.3, 0.3, 0.05);
    }

    private ServerPlayerEntity getHolder(MinecraftServer server) {
        return hotPotatoHolder != null ? server.getPlayerManager().getPlayer(hotPotatoHolder) : null;
    }

    private void reassignRandom(MinecraftServer server, List<ServerPlayerEntity> active) {
        if (active.isEmpty()) return;
        Collections.shuffle(active);
        hotPotatoHolder = active.get(0).getUuid();
        hotPotatoTimer  = randomTimer();
        applyHolderEffects(active.get(0));
    }

    private int randomTimer() {
        return MIN_TIMER_TICKS + random.nextInt(MAX_TIMER_TICKS - MIN_TIMER_TICKS);
    }
}
