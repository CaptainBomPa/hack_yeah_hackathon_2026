package pl.hackyeah.controllayer.ratelimit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** V3 has PostgreSQL-only audit triggers. Baseline old tables, then migrate real V7 on H2. */
@TestConfiguration(proxyBeanMethods = false)
public class RateLimitTestDatabase {
    @Bean
    FlywayMigrationStrategy rateTestMigration() {
        return flyway -> {
            if (flyway.info().applied().length == 0) {
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__app_user.sql"),
                        new ClassPathResource("db/migration/V4__budget_counter.sql"))
                        .execute(flyway.getConfiguration().getDataSource());
                flyway.baseline();
            }
            flyway.migrate();
        };
    }
}
