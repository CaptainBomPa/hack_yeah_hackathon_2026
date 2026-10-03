package pl.hackyeah.controllayer.guard;

import java.util.Map;

/**
 * Ustawienia guarda z aktywnej polityki (`guards.<ID>` w {@code PolicyDocument}). Liczby przyjmowane
 * są też jako napisy ("0.998") — Spring wiąże wartości z YAML do {@code Map<String, Object>} jako tekst,
 * a bez tego parametr byłby po cichu zastępowany wartością domyślną.
 */
public record GuardSettings(boolean enabled, Map<String, Object> params) {

    public GuardSettings {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public double doubleParam(String name, double defaultValue) {
        return switch (params.get(name)) {
            case Number number -> number.doubleValue();
            case String text -> parseOr(text, defaultValue);
            case null, default -> defaultValue;
        };
    }

    public String stringParam(String name, String defaultValue) {
        return params.get(name) instanceof String text ? text : defaultValue;
    }

    public int intParam(String name, int defaultValue) {
        return switch (params.get(name)) {
            case Number number -> number.intValue();
            case String text -> (int) parseOr(text, defaultValue);
            case null, default -> defaultValue;
        };
    }

    private static double parseOr(String text, double defaultValue) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
