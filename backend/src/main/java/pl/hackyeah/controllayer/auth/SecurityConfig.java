package pl.hackyeah.controllayer.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

/**
 * Uwierzytelnianie na lokalnych kontach (docs/auth), dwie drogi:
 * <ul>
 *   <li>przeglądarka: `POST /api/auth/login` zakłada sesję (ciasteczko `SESSION`, HttpOnly,
 *       SameSite=Lax) — `AuthController`;</li>
 *   <li>agenci, SDK, runner testów: HTTP Basic przy każdym żądaniu, bez sesji.</li>
 * </ul>
 * 401 nie wysyła `WWW-Authenticate`, więc przeglądarka nie pokazuje natywnego okienka logowania.
 *
 * CSRF wyłączony świadomie: ciasteczko sesji ma SameSite=Lax (cross-site POST go nie niesie),
 * a endpointy zmieniające stan przyjmują wyłącznie JSON, którego obca strona nie wyśle bez
 * preflightu CORS. Basic Auth nie używa ciasteczek, więc CSRF go nie dotyczy.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            ReactiveAuthenticationManager authenticationManager,
            ServerSecurityContextRepository securityContextRepository) {
        var unauthorized = new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(securityContextRepository)
                .authenticationManager(authenticationManager)
                .httpBasic(basic -> basic
                        .authenticationEntryPoint(unauthorized)
                        // Basic jest bezstanowy: bez tego globalne repozytorium zakładałoby sesję.
                        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance()))
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorized))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers("/api/auth/**").authenticated()
                        .pathMatchers("/api/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .build();
    }

    @Bean
    ReactiveAuthenticationManager authenticationManager(ReactiveUserDetailsService users,
            PasswordEncoder passwordEncoder) {
        var manager = new UserDetailsRepositoryReactiveAuthenticationManager(users);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    /** Kontekst bezpieczeństwa w WebSession — wspólny dla łańcucha filtrów i `AuthController`. */
    @Bean
    ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    /**
     * Ciasteczko sesji: HttpOnly (domyślnie) + jawne SameSite=Lax, na którym opiera się
     * wyłączenie CSRF. `Secure` z `AUTH_COOKIE_SECURE` — true tylko za HTTPS (docs/auth).
     */
    @Bean
    WebSessionIdResolver webSessionIdResolver(
            @Value("${AUTH_COOKIE_SECURE:false}") boolean secureCookie) {
        var resolver = new CookieWebSessionIdResolver();
        resolver.addCookieInitializer(cookie -> cookie.sameSite("Lax").httpOnly(true).secure(secureCookie));
        return resolver;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
