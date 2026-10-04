package pl.hackyeah.controllayer.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Wyniki łańcucha guardów dla już sprawdzonych treści. Klient czatu wysyła całą historię przy każdym
 * pytaniu, a guardy oceniają każdą wiadomość osobno (bez kontekstu), więc ta sama wiadomość z tą samą
 * polityką daje ten sam wynik — nie ma sensu odpytywać ponownie np. sidecara semantycznego.
 *
 * <p>Klucz: hash polityki + etap + SHA-256 treści (nie request id — ten jest inny w każdym żądaniu;
 * nie sama treść — w pamięci nie trzymamy surowych promptów). Wartość zawiera tylko tekst po redakcji
 * i trace. Nowa wersja polityki = nowe klucze, więc zmiana z UI działa od następnego żądania.
 * LRU z limitem wpisów i TTL.
 */
final class GuardResultCache {

    private record Stored(GuardChainResult result, long expiresAtNanos) {}

    private final int maxEntries;
    private final long ttlNanos;
    private final Map<String, Stored> entries;

    GuardResultCache(int maxEntries, Duration ttl) {
        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
                return size() > GuardResultCache.this.maxEntries;
            }
        };
    }

    static String key(String policyHash, Stage stage, String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return policyHash + ':' + stage + ':' + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized Optional<GuardChainResult> get(String key) {
        Stored stored = entries.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (System.nanoTime() - stored.expiresAtNanos() > 0) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(stored.result());
    }

    synchronized void put(String key, GuardChainResult result) {
        entries.put(key, new Stored(result, System.nanoTime() + ttlNanos));
    }
}
