package pl.hackyeah.controllayer.policy;

import java.time.Instant;

/** Skąd komponenty biorą aktywną politykę. W aplikacji to {@link PolicyStore}; w testach — polityka stała. */
@FunctionalInterface
public interface PolicySource {

    ActivePolicy current();

    /** Polityka, która się nie zmienia (wersja 0) — dla testów i konstruktorów z konfiguracji plikowej. */
    static PolicySource fixed(PolicyDocument document) {
        var policy = new ActivePolicy(0, PolicyJson.hash(document), document, "fixed", "fixed", null, Instant.EPOCH);
        return () -> policy;
    }
}
