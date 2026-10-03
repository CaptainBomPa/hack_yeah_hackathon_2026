package pl.hackyeah.controllayer.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.authentication.ServerHttpBasicAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/** Gateway login uses a separate header; Authorization belongs exclusively to Codex OAuth. */
@Configuration
@ConditionalOnProperty(name = IntegrationProperties.CODEX_ENABLED, havingValue = "true", matchIfMissing = true)
class CodexSecurityConfig {
    @Bean
    @Order(-1)
    SecurityWebFilterChain codexSecurityWebFilterChain(ServerHttpSecurity http,
            ReactiveAuthenticationManager authenticationManager) {
        var unauthorized = new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);
        var filter = new AuthenticationWebFilter(authenticationManager);
        var basic = new ServerHttpBasicAuthenticationConverter();
        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        filter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(unauthorized));
        filter.setServerAuthenticationConverter(exchange -> {
            var values = exchange.getRequest().getHeaders().get(CodexHeaders.GATEWAY_AUTH);
            if (values == null || values.size() != 1) return Mono.empty();
            // This copy is only used by the converter; the chain retains the original OAuth header.
            var credentials = exchange.mutate().request(request -> request.headers(headers ->
                    headers.set(HttpHeaders.AUTHORIZATION, values.getFirst()))).build();
            return basic.convert(credentials);
        });
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/v1/responses", "/v1/responses/compact", "/v1/models"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorized))
                .addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated()).build();
    }
}
