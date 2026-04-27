package com.cobblegames.rewards;

/**
 * Configuración del sistema de recompensas.
 * Se serializa dentro de settings.json como objeto "rewards".
 */
public class RewardConfig {

    /** Activar/desactivar el sistema de recompensas. */
    public boolean rewardsEnabled = true;

    /** Nombre de la moneda mostrado en mensajes. */
    public String currencyName = "impactors";

    /**
     * Comando ejecutado para dar la recompensa.
     * Placeholders disponibles: {player}, {amount}
     * Ejemplo impactor: "eco give {player} {amount}"
     * Ejemplo EssentialsX: "eco give {player} {amount}"
     */
    public String rewardCommand = "eco give {player} {amount}";

    /** Recompensa para el 1.er lugar. */
    public int firstPlaceReward = 500;

    /** Recompensa para el 2.º lugar. */
    public int secondPlaceReward = 250;

    /** Recompensa para el 3.er lugar. */
    public int thirdPlaceReward = 100;

    /** Recompensa por solo participar (4.º en adelante y eliminados). */
    public int participationReward = 25;
}
