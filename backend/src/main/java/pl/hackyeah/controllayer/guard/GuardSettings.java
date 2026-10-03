package pl.hackyeah.controllayer.guard;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Zbiór napisów: lista YAML, napis rozdzielony przecinkami ({@code "a, b"}) albo mapa indeksów
     * (tak Spring wiąże listy w {@code Map<String, Object>}). Brak parametru = {@code defaultValue}.
     */
    public Set<String> stringSetParam(String name, Set<String> defaultValue) {
        Collection<?> items = switch (params.get(name)) {
            case null -> null;
            case Collection<?> collection -> collection;
            case Map<?, ?> indexed -> indexed.values();
            case String text -> Arrays.asList(text.split(","));
            case Object other -> List.of(other);
        };
        if (items == null) {
            return defaultValue;
        }
        return items.stream().map(item -> String.valueOf(item).trim()).filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
