package pl.hackyeah.controllayer.ratelimit;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rate_limit_lease")
public class RateLimitLease {
    @Id
    @Column(name = "request_id")
    private UUID requestId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RateLimitLease() {}

    RateLimitLease(UUID requestId, UUID userId, Instant createdAt, Instant expiresAt) {
        this.requestId = requestId;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
