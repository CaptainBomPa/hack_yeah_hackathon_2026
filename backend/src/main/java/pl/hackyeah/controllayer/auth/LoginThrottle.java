package pl.hackyeah.controllayer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Limit nieudanych logowań per login (docs/auth: ryzyko słabych haseł demo). Po
 * {@value #MAX_FAILURES} błędach w oknie {@link #WINDOW} kolejne próby są odrzucane do końca
 * okna, także z poprawnym hasłem. Stan w pamięci — wystarcza dla jednej instancji gatewaya.
 */
@Component
class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    boolean isBlocked(String login) {
        Deque<Instant> recent = failures.get(key(login));
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            return recent.size() >= MAX_FAILURES;
        }
    }

    void recordFailure(String login) {
        Deque<Instant> recent = failures.computeIfAbsent(key(login), k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
    }

    void recordSuccess(String login) {
        failures.remove(key(login));
    }

    private void prune(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.pollFirst();
        }
    }

    private static String key(String login) {
        return login.trim().toLowerCase(Locale.ROOT);
    }
}
