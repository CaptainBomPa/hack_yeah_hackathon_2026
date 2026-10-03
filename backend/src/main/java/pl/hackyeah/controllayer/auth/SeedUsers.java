package pl.hackyeah.controllayer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.policy.PolicyProperties;

/**
 * Zakłada konta z `config/users.yaml` przy starcie, jeśli ich jeszcze nie ma. Idempotentne:
 * istniejące loginy nie są nadpisywane. Rola spoza polityki przerywa start, bo takie konto
 * nie miałoby żadnego dostępu i błąd byłby trudny do zauważenia.
 */
@Component
class SeedUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUsers.class);

    private final SeedUsersProperties seed;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PolicyProperties policy;

    SeedUsers(SeedUsersProperties seed, AppUserRepository users, PasswordEncoder passwordEncoder,
            PolicyProperties policy) {
        this.seed = seed;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed.enabled()) {
            return;
        }
        for (SeedUsersProperties.SeedUser user : seed.users()) {
            if (!policy.roles().containsKey(user.role())) {
                throw new IllegalStateException("konto startowe '" + user.login() + "': rola '" + user.role()
                        + "' nie istnieje w policy.roles");
            }
            if (users.findByLogin(user.login()).isPresent()) {
                continue;
            }
            users.save(new AppUser(user.login(), passwordEncoder.encode(user.password()), user.role()));
            log.info("seeded user login={} role={}", user.login(), user.role());
        }
    }
}
