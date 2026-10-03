package pl.hackyeah.controllayer.chat.upstream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Boot 4.1 nie dociąga automatycznie beana WebClient.Builder tylko z
 * spring-cloud-starter-gateway-server-webflux/spring-boot-starter-webflux — definiujemy go
 * jawnie zamiast polegać na autokonfiguracji, której moduł jest tu niepewny.
 */
@Configuration
class WebClientConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
