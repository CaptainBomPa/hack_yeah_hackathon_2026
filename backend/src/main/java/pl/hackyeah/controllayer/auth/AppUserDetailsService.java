package pl.hackyeah.controllayer.auth;

import java.util.Locale;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Ładuje konto z bazy dla Spring Security. JPA jest blokujące, więc odpytanie idzie na
 * `boundedElastic`, nie na event loopie WebFlux (VISION §2).
 */
@Component
class AppUserDetailsService implements ReactiveUserDetailsService {

    private final AppUserRepository users;

    AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Mono<UserDetails> findByUsername(String login) {
        return Mono.fromCallable(() -> users.findByLogin(login))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty)
                .map(AppUserDetailsService::toUserDetails);
    }

    private static UserDetails toUserDetails(AppUser user) {
        return User.withUsername(user.getLogin())
                .password(user.getPasswordHash())
                .roles(user.getRole().toUpperCase(Locale.ROOT))
                .disabled(!user.isEnabled())
                .build();
    }
}
