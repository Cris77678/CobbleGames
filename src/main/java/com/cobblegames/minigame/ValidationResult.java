package com.cobblegames.minigame;

import java.util.ArrayList;
import java.util.List;

/**
 * Resultado de la validación de configuración de un minijuego.
 * Si hay errores, el juego NO puede iniciar.
 */
public class ValidationResult {

    private final List<String> errors = new ArrayList<>();

    public void addError(String message) {
        errors.add(message);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    /** Genera un mensaje de error formateado listo para enviar al chat. */
    public String formatErrors(String gameDisplayName) {
        StringBuilder sb = new StringBuilder("§c[" + gameDisplayName + "] Configuración incompleta:\n");
        for (String err : errors) {
            sb.append("§c  ● ").append(err).append("\n");
        }
        sb.append("§7Avisa a un administrador para corregirlo.");
        return sb.toString();
    }
}
