package pl.hackyeah.controllayer.chat;

/**
 * Wynik pojedynczej kontroli dla jednego żądania — odpowiada `ControlTrace` z
 * frontend/src/api/types.ts oraz `ControlResult` z VISION.md §4 (tu już spłaszczony do tego,
 * co pokazuje UI; `confidence` i pełny `ControlResult` dojdą z resztą decision pipeline).
 */
public record ControlTrace(
        String policy,
        String kind, // "deterministic" | "semantic"
        String action, // "allow" | "redact" | "block"
        long latencyMs,
        String detail) {}
