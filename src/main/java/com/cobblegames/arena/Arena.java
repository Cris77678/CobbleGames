package com.cobblegames.arena;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Arena {

    private final String id;
    private String displayName;
    private String worldId;
    private BlockPos lobbyPos;
    private final List<BlockPos> spawnPoints   = new ArrayList<>();

    // ── Checkpoints (orden de vuelta — carrera) ───────────────────────────────
    private final List<BlockPos> checkpoints   = new ArrayList<>();

    // ── Boost pads (velocidad — carrera) ─────────────────────────────────────
    private final List<BlockPos> boostPositions = new ArrayList<>();

    // ── King of the Hill ─────────────────────────────────────────────────────
    private BlockPos hillCenter;
    private double   hillRadius = 5.0;

    // ── Meta (carreras / Red Light) ───────────────────────────────────────────
    private BlockPos finishLine;

    // ── Límites de arena (boundsMin ↔ boundsMax) ──────────────────────────────
    // Define el área de juego. Monedas, freeze, etc. se limitan a esta caja.
    private BlockPos boundsMin;
    private BlockPos boundsMax;

    // ── Puntos de spawn de monedas (Coin Collector) ───────────────────────────
    private final List<BlockPos> coinSpawnPoints = new ArrayList<>();

    public Arena(String id) {
        this.id          = id;
        this.displayName = id;
        this.worldId     = "minecraft:overworld";
    }

    // ─── Getters / Setters básicos ────────────────────────────────────────────

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }

    public RegistryKey<World> getWorldKey() {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId));
    }
    public String getWorldId()      { return worldId; }
    public void setWorldId(String v){ this.worldId = v; }

    public BlockPos getLobbyPos()        { return lobbyPos != null ? lobbyPos : BlockPos.ORIGIN; }
    public void setLobbyPos(BlockPos v)  { this.lobbyPos = v; }

    // ─── Spawns ───────────────────────────────────────────────────────────────

    public List<BlockPos> getSpawnPoints()  { return Collections.unmodifiableList(spawnPoints); }
    public void addSpawnPoint(BlockPos pos) { spawnPoints.add(pos); }
    public void clearSpawnPoints()          { spawnPoints.clear(); }
    public boolean removeSpawnPoint(int index) {
        if (index < 0 || index >= spawnPoints.size()) return false;
        spawnPoints.remove(index); return true;
    }

    // ─── Checkpoints de vuelta (carrera) ──────────────────────────────────────

    /** Añade el siguiente checkpoint de vuelta al final de la lista ordenada. */
    public void addCheckpoint(BlockPos pos)  { checkpoints.add(pos); }
    public List<BlockPos> getCheckpoints()   { return Collections.unmodifiableList(checkpoints); }
    public void clearCheckpoints()           { checkpoints.clear(); }
    /** Elimina el checkpoint en la posición dada de la lista. */
    public boolean removeCheckpoint(int index) {
        if (index < 0 || index >= checkpoints.size()) return false;
        checkpoints.remove(index); return true;
    }

    // ─── Boost pads (carrera) ─────────────────────────────────────────────────

    /** Añade un boost pad de velocidad. */
    public void addBoostPosition(BlockPos pos)  { boostPositions.add(pos); }
    public List<BlockPos> getBoostPositions()   { return Collections.unmodifiableList(boostPositions); }
    public void clearBoostPositions()            { boostPositions.clear(); }
    public boolean removeBoostPosition(int index) {
        if (index < 0 || index >= boostPositions.size()) return false;
        boostPositions.remove(index); return true;
    }

    // ─── King of the Hill ─────────────────────────────────────────────────────

    public BlockPos getHillCenter()          { return hillCenter; }
    public void setHillCenter(BlockPos v)    { this.hillCenter = v; }
    public double getHillRadius()            { return hillRadius; }
    public void setHillRadius(double v)      { this.hillRadius = v; }

    // ─── Meta ─────────────────────────────────────────────────────────────────

    public BlockPos getFinishLine()          { return finishLine; }
    public void setFinishLine(BlockPos v)    { this.finishLine = v; }

    // ─── Límites (bounds) ─────────────────────────────────────────────────────

    public BlockPos getBoundsMin()           { return boundsMin; }
    // BUG #8 FIX: setters individuales solo asignan el valor directamente.
    // La normalización ocurre únicamente en setBounds(a, b) (usado por comandos y ConfigLoader).
    public void setBoundsMin(BlockPos v)     { this.boundsMin = v; }
    public BlockPos getBoundsMax()           { return boundsMax; }
    public void setBoundsMax(BlockPos v)     { this.boundsMax = v; }

    /**
     * Establece ambos extremos a la vez, normalizando automáticamente
     * para que min siempre tenga las coordenadas menores.
     */
    public void setBounds(BlockPos a, BlockPos b) {
        this.boundsMin = new BlockPos(
            Math.min(a.getX(), b.getX()),
            Math.min(a.getY(), b.getY()),
            Math.min(a.getZ(), b.getZ()));
        this.boundsMax = new BlockPos(
            Math.max(a.getX(), b.getX()),
            Math.max(a.getY(), b.getY()),
            Math.max(a.getZ(), b.getZ()));
    }

    /** true si los bounds están configurados. */
    public boolean hasBounds() { return boundsMin != null && boundsMax != null; }

    /** Elimina los bounds completamente. */
    public void clearBounds() { this.boundsMin = null; this.boundsMax = null; }

    /** true si la posición está dentro de los límites. Si no hay bounds, devuelve true. */
    public boolean isInBounds(BlockPos pos) {
        if (!hasBounds()) return true;
        return pos.getX() >= boundsMin.getX() && pos.getX() <= boundsMax.getX()
            && pos.getY() >= boundsMin.getY() && pos.getY() <= boundsMax.getY()
            && pos.getZ() >= boundsMin.getZ() && pos.getZ() <= boundsMax.getZ();
    }

    /** Tamaño en bloques de la arena en cada eje (0 si no hay bounds). */
    public int boundsWidth()  { return hasBounds() ? boundsMax.getX() - boundsMin.getX() + 1 : 0; }
    public int boundsHeight() { return hasBounds() ? boundsMax.getY() - boundsMin.getY() + 1 : 0; }
    public int boundsDepth()  { return hasBounds() ? boundsMax.getZ() - boundsMin.getZ() + 1 : 0; }

    // ─── Monedas ──────────────────────────────────────────────────────────────

    public List<BlockPos> getCoinSpawnPoints()  { return Collections.unmodifiableList(coinSpawnPoints); }
    public void addCoinSpawnPoint(BlockPos pos) { coinSpawnPoints.add(pos); }
    public void clearCoinSpawnPoints()          { coinSpawnPoints.clear(); }
    public boolean removeCoinSpawnPoint(int index) {
        if (index < 0 || index >= coinSpawnPoints.size()) return false;
        coinSpawnPoints.remove(index); return true;
    }

    // ─── King of the Hill ─────────────────────────────────────────────────────

    /** Verifica si un jugador está en la zona de King of the Hill. */
    public boolean isInHillZone(BlockPos pos) {
        if (hillCenter == null) return false;
        double dx = pos.getX() - hillCenter.getX();
        double dz = pos.getZ() - hillCenter.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= hillRadius;
    }

    // ─── Normalización interna ────────────────────────────────────────────────

    private static BlockPos normMin(BlockPos a, BlockPos b) {
        if (a == null || b == null) return a;
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }
    private static BlockPos normMax(BlockPos a, BlockPos b) {
        if (a == null || b == null) return b;
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
}
