package pl.hackyeah.controllayer.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Logowanie przeglądarki (docs/auth, D2): konto lokalne → sesja w ciasteczku `SESSION`.
 * Agenci i runner testów dalej używają HTTP Basic (SecurityConfig).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String ROLE_PREFIX = "ROLE_";

    private final ReactiveAuthenticationManager authenticationManager;
    private final ServerSecurityContextRepository securityContextRepository;
    private final LoginThrottle throttle;

    AuthController(ReactiveAuthenticationManager authenticationManager,
            ServerSecurityContextRepository securityContextRepository, LoginThrottle throttle) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.throttle = throttle;
    }

    record LoginRequest(@NotBlank @Size(max = 100) String login, @NotBlank @Size(max = 200) String password) {}

    /** Zalogowany użytkownik — `CurrentUser` z frontend/src/api/types.ts. */
    record CurrentUser(String login, String role) {}

    @PostMapping("/login")
    Mono<ResponseEntity<Object>> login(@Valid @RequestBody LoginRequest request, ServerWebExchange exchange) {
        if (throttle.isBlocked(request.login())) {
            log.warn("login throttled login={}", request.login());
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .<Object>body(error("too_many_attempts", "Too many failed attempts. Try again in a minute.")));
        }
        var token = UsernamePasswordAuthenticationToken.unauthenticated(request.login(), request.password());
        return authenticationManager.authenticate(token)
                .flatMap(authentication -> exchange.getSession()
                        // Nowy identyfikator sesji po zalogowaniu (ochrona przed session fixation).
                        .flatMap(session -> session.changeSessionId())
                        .then(securityContextRepository.save(exchange, new SecurityContextImpl(authentication)))
                        .then(Mono.fromSupplier(() -> {
                            throttle.recordSuccess(request.login());
                            log.info("login ok login={}", authentication.getName());
                            return ResponseEntity.<Object>ok(currentUser(authentication));
                        })))
                .onErrorResume(AuthenticationException.class, error -> {
                    throttle.recordFailure(request.login());
                    log.info("login failed login={} reason={}", request.login(), error.getClass().getSimpleName());
                    // Ten sam komunikat dla złego loginu, hasła i wyłączonego konta — bez enumeracji kont.
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .<Object>body(error("invalid_credentials", "Invalid username or password.")));
                });
    }

    @GetMapping("/me")
    Mono<CurrentUser> me(Authentication authentication) {
        return Mono.just(currentUser(authentication));
    }

    @PostMapping("/logout")
    Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return exchange.getSession()
                .flatMap(session -> session.invalidate())
                .thenReturn(ResponseEntity.noContent().build());
    }

    private static CurrentUser currentUser(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()).toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse(null);
        return new CurrentUser(authentication.getName(), role);
    }

    private static Map<String, Map<String, String>> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
