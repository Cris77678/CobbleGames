package com.cobblegames.config;

import com.cobblegames.arena.Arena;
import com.cobblegames.arena.ArenaManager;
import com.cobblegames.core.CobbleGames;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ConfigLoader {

    private final Path configDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private CobbleGamesSettings settings = new CobbleGamesSettings();

    public ConfigLoader(MinecraftServer server) {
        this.configDir = server.getRunDirectory()
            .resolve("config").resolve("cobblegames");
    }

    public void loadAll() {
        try {
            Files.createDirectories(configDir.resolve("arenas"));
            loadSettings();
            CobbleGames.LOGGER.info("[CobbleGames] Configuración cargada desde: {}", configDir);
        } catch (IOException e) {
            CobbleGames.LOGGER.error("[CobbleGames] Error creando directorio de configuración", e);
        }
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    private void loadSettings() {
        Path settingsFile = configDir.resolve("settings.json");
        if (!Files.exists(settingsFile)) {
            saveSettings();
            return;
        }
        try (Reader reader = new FileReader(settingsFile.toFile(), StandardCharsets.UTF_8)) {
            CobbleGamesSettings loaded = gson.fromJson(reader, CobbleGamesSettings.class);
            // BUG #1 FIX: nunca asignar null si Gson devuelve objeto parcial
            if (loaded != null) {
                settings = loaded;
                // Asegurar sub-objetos no nulos (campos faltantes en el JSON)
                if (settings.rewards == null) {
                    settings.rewards = new com.cobblegames.rewards.RewardConfig();
                    CobbleGames.LOGGER.warn("[CobbleGames] settings.json no tiene 'rewards', usando valores por defecto.");
                }
            }
        } catch (IOException e) {
            CobbleGames.LOGGER.warn("[CobbleGames] No se pudo leer settings.json, usando valores por defecto.");
        }
    }

    private void saveSettings() {
        try (Writer writer = new FileWriter(configDir.resolve("settings.json").toFile(), StandardCharsets.UTF_8)) {
            gson.toJson(settings, writer);
        } catch (IOException e) {
            CobbleGames.LOGGER.error("[CobbleGames] No se pudo guardar settings.json", e);
        }
    }

    // ─── Arenas ───────────────────────────────────────────────────────────────

    public void loadArenas(ArenaManager manager) {
        Path arenasDir = configDir.resolve("arenas");
        try {
            if (!Files.exists(arenasDir)) return;
            Files.list(arenasDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    Arena arena = loadArenaFile(p);
                    if (arena != null) manager.registerArena(arena);
                });
        } catch (IOException e) {
            CobbleGames.LOGGER.error("[CobbleGames] Error leyendo arenas", e);
        }
    }

    private Arena loadArenaFile(Path path) {
        try (Reader reader = new FileReader(path.toFile(), StandardCharsets.UTF_8)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            String id = json.get("id").getAsString();
            Arena arena = new Arena(id);

            if (json.has("displayName"))
                arena.setDisplayName(json.get("displayName").getAsString());
            if (json.has("world"))
                arena.setWorldId(json.get("world").getAsString());
            if (json.has("lobby"))
                arena.setLobbyPos(readPos(json.getAsJsonObject("lobby")));
            if (json.has("hillCenter"))
                arena.setHillCenter(readPos(json.getAsJsonObject("hillCenter")));
            if (json.has("hillRadius"))
                arena.setHillRadius(json.get("hillRadius").getAsDouble());
            if (json.has("finishLine"))
                arena.setFinishLine(readPos(json.getAsJsonObject("finishLine")));

            // BUG #11 FIX: cargar bounds juntos para normalización correcta
            if (json.has("boundsMin") && json.has("boundsMax")) {
                arena.setBounds(
                    readPos(json.getAsJsonObject("boundsMin")),
                    readPos(json.getAsJsonObject("boundsMax"))
                );
            } else if (json.has("boundsMin")) {
                arena.setBoundsMin(readPos(json.getAsJsonObject("boundsMin")));
            } else if (json.has("boundsMax")) {
                arena.setBoundsMax(readPos(json.getAsJsonObject("boundsMax")));
            }

            readPosList(json, "spawns").forEach(arena::addSpawnPoint);
            readPosList(json, "checkpoints").forEach(arena::addCheckpoint);
            readPosList(json, "coinSpawns").forEach(arena::addCoinSpawnPoint);
            readPosList(json, "boostPositions").forEach(arena::addBoostPosition);

            return arena;
        } catch (Exception e) {
            CobbleGames.LOGGER.error("[CobbleGames] Error leyendo arena: {}", path, e);
            return null;
        }
    }

    public void saveArena(Arena arena) {
        Path file = configDir.resolve("arenas").resolve(arena.getId() + ".json");
        JsonObject json = new JsonObject();
        json.addProperty("id", arena.getId());
        json.addProperty("displayName", arena.getDisplayName());
        json.addProperty("world", arena.getWorldId());

        if (arena.getLobbyPos() != null && !arena.getLobbyPos().equals(BlockPos.ORIGIN))
            json.add("lobby", writePos(arena.getLobbyPos()));
        if (arena.getHillCenter() != null)
            json.add("hillCenter", writePos(arena.getHillCenter()));
        json.addProperty("hillRadius", arena.getHillRadius());
        if (arena.getFinishLine() != null)
            json.add("finishLine", writePos(arena.getFinishLine()));
        if (arena.getBoundsMin() != null)
            json.add("boundsMin", writePos(arena.getBoundsMin()));
        if (arena.getBoundsMax() != null)
            json.add("boundsMax", writePos(arena.getBoundsMax()));

        JsonArray spawns = new JsonArray();
        arena.getSpawnPoints().forEach(p -> spawns.add(writePos(p)));
        json.add("spawns", spawns);

        JsonArray checkpoints = new JsonArray();
        arena.getCheckpoints().forEach(p -> checkpoints.add(writePos(p)));
        json.add("checkpoints", checkpoints);

        JsonArray coinSpawns = new JsonArray();
        arena.getCoinSpawnPoints().forEach(p -> coinSpawns.add(writePos(p)));
        json.add("coinSpawns", coinSpawns);

        JsonArray boosts = new JsonArray();
        arena.getBoostPositions().forEach(p -> boosts.add(writePos(p)));
        json.add("boostPositions", boosts);

        try (Writer writer = new FileWriter(file.toFile(), StandardCharsets.UTF_8)) {
            gson.toJson(json, writer);
        } catch (IOException e) {
            CobbleGames.LOGGER.error("[CobbleGames] No se pudo guardar arena: {}", arena.getId(), e);
        }
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    private BlockPos readPos(JsonObject obj) {
        return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
    }

    private JsonObject writePos(BlockPos pos) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        return obj;
    }

    private java.util.List<BlockPos> readPosList(JsonObject json, String key) {
        java.util.List<BlockPos> list = new java.util.ArrayList<>();
        if (json.has(key)) {
            json.getAsJsonArray(key).forEach(e -> list.add(readPos(e.getAsJsonObject())));
        }
        return list;
    }

    public CobbleGamesSettings getSettings() { return settings; }
}
