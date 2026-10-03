package pl.hackyeah.controllayer.ratelimit;

import javax.sql.DataSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Local profile disables Flyway; use the exact production rate-limit migration. */
@Configuration
@Profile("local")
@DependsOn("entityManagerFactory")
class LocalRateLimitSchema {
    LocalRateLimitSchema(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__rate_limit.sql"))
                .execute(dataSource);
    }
}
