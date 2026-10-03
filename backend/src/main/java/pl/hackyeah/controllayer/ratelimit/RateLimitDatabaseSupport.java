package pl.hackyeah.controllayer.ratelimit;

import java.time.Instant;
import java.time.OffsetDateTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.stereotype.Repository;

/** Database-specific lock timeout and clock; PostgreSQL CURRENT_TIMESTAMP freezes at transaction start. */
@Repository
public class RateLimitDatabaseSupport {
    static final int TRANSACTION_TIMEOUT_SECONDS = 3;
    private final EntityManager entityManager;
    private final boolean postgres;

    public RateLimitDatabaseSupport(EntityManager entityManager, EntityManagerFactory factory) {
        this.entityManager = entityManager;
        postgres = factory.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect()
                instanceof PostgreSQLDialect;
    }

    void configureLockTimeout() {
        if (postgres) entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }

    Instant now() {
        return ((OffsetDateTime) entityManager.createNativeQuery(
                postgres ? "SELECT clock_timestamp()" : "SELECT CURRENT_TIMESTAMP", OffsetDateTime.class)
                .setHint("jakarta.persistence.query.timeout", TRANSACTION_TIMEOUT_SECONDS * 1000)
                .getSingleResult()).toInstant();
    }
}
