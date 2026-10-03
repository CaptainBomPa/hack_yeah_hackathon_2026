package pl.hackyeah.controllayer.ratelimit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateLimitLeaseRepository extends JpaRepository<RateLimitLease, UUID> {
    long countByUserId(UUID userId);

    @Modifying
    @Query("delete from RateLimitLease l where l.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from RateLimitLease l where l.requestId = :requestId")
    int release(@Param("requestId") UUID requestId);
}
