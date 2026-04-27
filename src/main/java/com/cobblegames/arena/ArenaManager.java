package com.cobblegames.arena;

import com.cobblegames.config.ConfigLoader;
import com.cobblegames.core.CobbleGames;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona el ciclo de vida de las arenas: carga, registro y consulta.
 */
public class ArenaManager {

    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
    private final ConfigLoader configLoader;

    public ArenaManager(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        loadArenas();
    }

    private void loadArenas() {
        configLoader.loadArenas(this);
        if (arenas.isEmpty()) {
            // Crear una arena de ejemplo si no hay ninguna configurada
            Arena example = new Arena("example_arena");
            example.setDisplayName("Arena de Ejemplo");
            registerArena(example);
            CobbleGames.LOGGER.info("[CobbleGames] No se encontraron arenas. Arena de ejemplo creada.");
        }
    }

    public void registerArena(Arena arena) {
        arenas.put(arena.getId(), arena);
        CobbleGames.LOGGER.info("[CobbleGames] Arena registrada: {}", arena.getId());
    }

    public Arena getArena(String id) {
        return arenas.get(id);
    }

    public Collection<Arena> getAllArenas() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    public int getArenaCount() {
        return arenas.size();
    }

    public boolean exists(String id) {
        return arenas.containsKey(id);
    }
}
