package com.cobblegames.rewards;

import com.cobblegames.announce.AnnouncementSystem;
import com.cobblegames.core.CobbleGames;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.UUID;

public class RewardSystem {

    private final RewardConfig config;

    public RewardSystem(RewardConfig config) {
        this.config = config != null ? config : new RewardConfig();
    }

    public void giveRewards(MinecraftServer server, List<UUID> orderedUuids, String gameDisplayName) {
        if (!config.rewardsEnabled) return;
        for (int i = 0; i < orderedUuids.size(); i++) {
            UUID uuid = orderedUuids.get(i);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            int amount = getRewardForPosition(i + 1);
            if (amount <= 0) continue;
            if (player != null) {
                boolean success = runRewardCommand(server, player.getName().getString(), amount);
                if (success) {
                    AnnouncementSystem.announceTo(player,
                        "§6[Recompensa] §e+" + amount + " " + config.currencyName
                        + " §7por " + positionLabel(i + 1) + " en §e" + gameDisplayName);
                } else {
                    // BUG #14 FIX: notificar al jugador si la recompensa falló
                    AnnouncementSystem.announceTo(player,
                        "§c[Recompensa] No se pudo entregar la recompensa. Avisa a un admin.");
                    CobbleGames.LOGGER.error(
                        "[CobbleGames] Recompensa fallida para {} ({} {}) en {}",
                        player.getName().getString(), amount, config.currencyName, gameDisplayName);
                }
            }
        }
    }

    public void giveParticipationRewards(MinecraftServer server,
                                          List<UUID> allPlayers,
                                          List<UUID> alreadyRewarded,
                                          String gameDisplayName) {
        if (!config.rewardsEnabled || config.participationReward <= 0) return;
        for (UUID uuid : allPlayers) {
            if (alreadyRewarded.contains(uuid)) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                boolean success = runRewardCommand(server, player.getName().getString(), config.participationReward);
                if (success) {
                    AnnouncementSystem.announceTo(player,
                        "§6[Recompensa] §e+" + config.participationReward + " " + config.currencyName
                        + " §7por participar en §e" + gameDisplayName);
                }
            }
        }
    }

    private int getRewardForPosition(int position) {
        return switch (position) {
            case 1 -> config.firstPlaceReward;
            case 2 -> config.secondPlaceReward;
            case 3 -> config.thirdPlaceReward;
            default -> config.participationReward;
        };
    }

    private String positionLabel(int position) {
        return switch (position) {
            case 1 -> "§61.º lugar";
            case 2 -> "§72.º lugar";
            case 3 -> "§c3.º lugar";
            default -> "§7" + position + ".º lugar";
        };
    }

    /**
     * BUG #14 FIX: devuelve true si el comando se ejecutó sin excepción.
     * El jugador ya NO recibe el mensaje de recompensa si el comando falló.
     */
    private boolean runRewardCommand(MinecraftServer server, String playerName, int amount) {
        String cmd = config.rewardCommand
            .replace("{player}", playerName)
            .replace("{amount}", String.valueOf(amount));
        try {
            server.getCommandManager().executeWithPrefix(
                server.getCommandSource().withSilent().withMaxLevel(4), cmd);
            return true;
        } catch (Exception e) {
            CobbleGames.LOGGER.error("[CobbleGames] Error ejecutando recompensa '{}': {}", cmd, e.getMessage());
            return false;
        }
    }
}
