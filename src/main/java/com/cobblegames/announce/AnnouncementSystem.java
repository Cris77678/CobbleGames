package com.cobblegames.announce;

import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/**
 * Sistema centralizado de anuncios.
 * Todos los mensajes/sonidos se envían SOLO a jugadores en partida.
 *
 * NOTA sobre SoundEvents en Yarn 1.21.1:
 *   Los campos de SoundEvents (p.ej. SoundEvents.ENTITY_GENERIC_EXPLODE) son
 *   RegistryEntry<SoundEvent>, NO SoundEvent directos.
 *   PlaySoundS2CPacket acepta RegistryEntry<SoundEvent> — usamos esa sobrecarga.
 *   Si tienes un SoundEvent directo usa RegistryEntry.of(sound) para envolverlo.
 */
public final class AnnouncementSystem {

    private AnnouncementSystem() {}

    // ─── Chat ─────────────────────────────────────────────────────────────────

    public static void announce(MinecraftServer server, List<UUID> playerIds, String message) {
        Text text = Text.literal(message);
        for (UUID uuid : playerIds) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) player.sendMessage(text, false);
        }
    }

    public static void announceTo(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), false);
    }

    // ─── Título ───────────────────────────────────────────────────────────────

    public static void announceTitle(MinecraftServer server, List<UUID> playerIds,
                                     String title, String subtitle) {
        Text t = Text.literal(title);
        Text s = Text.literal(subtitle);
        for (UUID uuid : playerIds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) sendTitle(p, t, s);
        }
    }

    public static void announceTitleTo(ServerPlayerEntity player, String title, String subtitle) {
        sendTitle(player, Text.literal(title), Text.literal(subtitle));
    }

    // ─── ActionBar ────────────────────────────────────────────────────────────

    public static void announceActionBar(MinecraftServer server, List<UUID> playerIds, String message) {
        Text text = Text.literal(message);
        for (UUID uuid : playerIds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) p.sendMessage(text, true);
        }
    }

    public static void announceActionBarTo(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), true);
    }

    // ─── Sonido — acepta SoundEvent directo ──────────────────────────────────

    public static void announceSound(MinecraftServer server, List<UUID> playerIds,
                                     SoundEvent sound, float volume, float pitch) {
        announceSound(server, playerIds, RegistryEntry.of(sound), volume, pitch);
    }

    public static void announceSoundTo(ServerPlayerEntity player,
                                       SoundEvent sound, float volume, float pitch) {
        playSoundToPlayer(player, RegistryEntry.of(sound), SoundCategory.PLAYERS, volume, pitch);
    }

    // ─── Sonido — acepta RegistryEntry<SoundEvent> (SoundEvents.* en 1.21.1) ─

    public static void announceSound(MinecraftServer server, List<UUID> playerIds,
                                     RegistryEntry<SoundEvent> sound, float volume, float pitch) {
        for (UUID uuid : playerIds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) playSoundToPlayer(p, sound, SoundCategory.PLAYERS, volume, pitch);
        }
    }

    public static void announceSoundTo(ServerPlayerEntity player,
                                       RegistryEntry<SoundEvent> sound, float volume, float pitch) {
        playSoundToPlayer(player, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    // ─── Música — acepta SoundEvent directo ──────────────────────────────────

    public static void startMusic(MinecraftServer server, List<UUID> playerIds,
                                  SoundEvent sound, float volume) {
        startMusic(server, playerIds, RegistryEntry.of(sound), volume);
    }

    public static void startMusicTo(ServerPlayerEntity player, SoundEvent sound, float volume) {
        playSoundToPlayer(player, RegistryEntry.of(sound), SoundCategory.MUSIC, volume, 1.0f);
    }

    // ─── Música — acepta RegistryEntry<SoundEvent> ───────────────────────────

    public static void startMusic(MinecraftServer server, List<UUID> playerIds,
                                  RegistryEntry<SoundEvent> sound, float volume) {
        for (UUID uuid : playerIds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) playSoundToPlayer(p, sound, SoundCategory.MUSIC, volume, 1.0f);
        }
    }

    public static void startMusicTo(ServerPlayerEntity player,
                                    RegistryEntry<SoundEvent> sound, float volume) {
        playSoundToPlayer(player, sound, SoundCategory.MUSIC, volume, 1.0f);
    }

    public static void stopMusic(MinecraftServer server, List<UUID> playerIds) {
        for (UUID uuid : playerIds) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) stopMusicFor(p);
        }
    }

    public static void stopMusicFor(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new StopSoundS2CPacket(null, SoundCategory.MUSIC));
    }

    // ─── Broadcast global (NO usar en minijuegos) ─────────────────────────────

    public static void broadcast(MinecraftServer server, String message) {
        server.getPlayerManager().broadcast(Text.literal(message), false);
    }

    // ─── Privado ──────────────────────────────────────────────────────────────

    private static void sendTitle(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 40, 20));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
    }

    private static void playSoundToPlayer(ServerPlayerEntity player,
                                          RegistryEntry<SoundEvent> sound,
                                          SoundCategory category,
                                          float volume, float pitch) {
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
            sound, category,
            player.getX(), player.getY(), player.getZ(),
            volume, pitch,
            player.getRandom().nextLong()
        ));
    }
}
