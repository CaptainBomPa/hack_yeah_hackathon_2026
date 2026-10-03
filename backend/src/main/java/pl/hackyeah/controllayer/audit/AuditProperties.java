package pl.hackyeah.controllayer.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracja audytu (`control-layer.audit`).
 *
 * @param chainKey       klucz HMAC łańcucha rekordów; w prod z env `AUDIT_CHAIN_KEY`
 * @param failClosed     true = gdy zapis audytu się nie uda, żądanie kończy się 503 zamiast
 *                       odpowiedzi modelu (AUDIT-008, NIST AU-5)
 * @param maxFieldLength limit długości pól niezaufanych w rekordzie
 */
@ConfigurationProperties(prefix = "control-layer.audit")
public record AuditProperties(String chainKey, Boolean failClosed, Integer maxFieldLength) {

    public AuditProperties {
        if (chainKey == null || chainKey.isBlank()) {
            chainKey = "dev-only-audit-chain-key";
        }
        if (failClosed == null) {
            failClosed = true;
        }
        if (maxFieldLength == null) {
            maxFieldLength = 256;
        }
    }
}
