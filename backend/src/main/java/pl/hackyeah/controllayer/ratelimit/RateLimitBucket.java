package pl.hackyeah.controllayer.ratelimit;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rate_limit_bucket")
public class RateLimitBucket {
    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(nullable = false)
    private double tokens;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "full_at", nullable = false)
    private Instant fullAt;

    protected RateLimitBucket() {}

    RateLimitBucket(UUID userId) { this.userId = userId; }

    double tokens() { return tokens; }
    Instant updatedAt() { return updatedAt; }

    void consume(double remaining, Instant now, Instant fullAt) {
        this.tokens = remaining;
        this.updatedAt = now;
        this.fullAt = fullAt;
    }
}
