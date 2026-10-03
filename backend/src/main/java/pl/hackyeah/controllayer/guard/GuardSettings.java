package pl.hackyeah.controllayer.guard;

import java.util.Map;

/** Ustawienia guarda z `control-layer.guards.rules.<ID>` w YAML. */
public record GuardSettings(boolean enabled, Map<String, Object> params) {

    public GuardSettings {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public double doubleParam(String name, double defaultValue) {
        return params.get(name) instanceof Number number ? number.doubleValue() : defaultValue;
    }

    public int intParam(String name, int defaultValue) {
        return params.get(name) instanceof Number number ? number.intValue() : defaultValue;
    }
}
