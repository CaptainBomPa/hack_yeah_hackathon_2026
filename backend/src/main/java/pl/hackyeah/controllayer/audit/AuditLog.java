package pl.hackyeah.controllayer.audit;

/**
 * Zapis audytu z punktu widzenia pipeline'u. Wywołanie jest blokujące (JPA) — wołać poza
 * event loopem WebFlux (VISION.md §2). Wyjątek oznacza, że rekord nie został zapisany.
 */
public interface AuditLog {

    void append(AuditEntry entry);
}
