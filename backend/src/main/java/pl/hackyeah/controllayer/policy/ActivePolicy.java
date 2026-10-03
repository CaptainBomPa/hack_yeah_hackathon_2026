package pl.hackyeah.controllayer.policy;

import java.time.Instant;

/**
 * Jedna wersja polityki. Żądanie bierze snapshot raz na początku ({@link PolicySource#current()}),
 * więc w trakcie jednego żądania polityka się nie zmienia, nawet gdy admin właśnie zapisał nową.
 *
 * @param version kolejny numer wersji (0 = polityka stała, np. w testach jednostkowych)
 * @param source  seed | ui | import | restore | fixed
 */
public record ActivePolicy(
        long version,
        String hash,
        PolicyDocument document,
        String author,
        String source,
        String comment,
        Instant createdAt) {}
