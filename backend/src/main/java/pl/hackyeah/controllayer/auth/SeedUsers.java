package pl.hackyeah.controllayer.auth;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.policy.PolicyStore;

/**
 * Zakłada konta z `config/users.yaml` przy starcie, jeśli ich jeszcze nie ma. Idempotentne:
 * istniejące loginy nie są nadpisywane. Rola, której brakuje w polityce w bazie (np. nowa rola po
 * wdrożeniu na istniejącą bazę), jest dopisywana z policy.yaml ({@link PolicyStore#ensureRoles});
 * rola nieznana także tam przerywa start, bo takie konto nie miałoby żadnego dostępu.
 */
@Component
class SeedUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUsers.class);

    private final SeedUsersProperties seed;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PolicyStore policy;

    SeedUsers(SeedUsersProperties seed, AppUserRepository users, PasswordEncoder passwordEncoder,
            PolicyStore policy) {
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
        policy.ensureRoles(seed.users().stream().map(SeedUsersProperties.SeedUser::role).collect(Collectors.toSet()));
        for (SeedUsersProperties.SeedUser user : seed.users()) {
            if (users.findByLogin(user.login()).isPresent()) {
                continue;
            }
            users.save(new AppUser(user.login(), passwordEncoder.encode(user.password()), user.role()));
            log.info("seeded user login={} role={}", user.login(), user.role());
        }
    }
}
