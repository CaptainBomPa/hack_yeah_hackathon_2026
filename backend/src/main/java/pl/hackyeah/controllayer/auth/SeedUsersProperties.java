package pl.hackyeah.controllayer.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konta startowe z `config/users.yaml` (klucz `control-layer.seed`). Służą demo i testom (local i
 * docker-compose); we wdrożeniu produkcyjnym `enabled: false`, a konta tworzy scripts/add-user.sh.
 */
@ConfigurationProperties(prefix = "control-layer.seed")
public record SeedUsersProperties(boolean enabled, List<SeedUser> users) {

    public SeedUsersProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record SeedUser(String login, String password, String role) {
    }
}
