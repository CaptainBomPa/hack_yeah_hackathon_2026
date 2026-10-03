package pl.hackyeah.controllayer.guard;

import java.util.Set;

/**
 * Jedna reguła deterministycznej kontroli (np. PII-001). Implementacja to zwykły bean
 * (`@Component`); `GuardChain` sam ją znajdzie, a włącza się ją w `control-layer.guards.rules`
 * pod kluczem `id()`. Guard musi być bezstanowy i thread-safe. Jak pisać guardy:
 * docs/deterministic/how-to-write-a-rule.md.
 */
public interface Guard {

    /** Stabilne id z case file, np. "PII-001" — klucz w YAML i nazwa polityki w `trace`. */
    String id();

    /** Etapy, na których guard działa. */
    Set<Stage> stages();

    /** Sprawdza `ctx.text()`. Wyjątek jest traktowany jak {@link Verdict.Block} (fail-closed). */
    Verdict check(GuardContext ctx, GuardSettings settings);
}
