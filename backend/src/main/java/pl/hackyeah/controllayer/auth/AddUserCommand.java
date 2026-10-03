package pl.hackyeah.controllayer.auth;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import pl.hackyeah.controllayer.policy.PolicyProperties;

/**
 * Tworzy konto i kończy działanie. Uruchamiane przez scripts/add-user.sh z parametrami
 * `--control-layer.cli.add-user` i `--control-layer.cli.role`. Hasło idzie przez zmienną
 * `CL_NEW_PASSWORD`, nie przez argumenty, żeby nie trafiło do historii powłoki ani do `ps`.
 * Aplikacja startuje normalnie (web-application-type=none psuje Spring Cloud Gateway), więc
 * skrypt podaje `--server.port=0`, a po zapisie proces zamyka się sam.
 */
@Component
@ConditionalOnProperty("control-layer.cli.add-user")
class AddUserCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AddUserCommand.class);
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final String PASSWORD_ENV = "CL_NEW_PASSWORD";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PolicyProperties policy;
    private final ConfigurableApplicationContext context;
    private final String login;
    private final String role;

    AddUserCommand(AppUserRepository users, PasswordEncoder passwordEncoder, PolicyProperties policy,
            ConfigurableApplicationContext context,
            @Value("${control-layer.cli.add-user}") String login,
            @Value("${control-layer.cli.role:}") String role) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
        this.context = context;
        this.login = login;
        this.role = role == null ? "" : role.toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!policy.roles().containsKey(role)) {
            throw new IllegalArgumentException("rola '" + role + "' nie istnieje w policy.roles: " + policy.roles().keySet());
        }
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(PASSWORD_ENV + " musi mieć co najmniej " + MIN_PASSWORD_LENGTH + " znaków");
        }
        if (users.findByLogin(login).isPresent()) {
            throw new IllegalStateException("konto '" + login + "' już istnieje");
        }
        users.save(new AppUser(login, passwordEncoder.encode(password), role));
        log.info("added user login={} role={}", login, role);
        System.exit(SpringApplication.exit(context));
    }
}
