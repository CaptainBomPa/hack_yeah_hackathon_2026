package pl.hackyeah.controllayer.ratelimit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateLimitBucketRepository extends JpaRepository<RateLimitBucket, UUID> {
    @Modifying
    @Query("delete from RateLimitBucket b where b.fullAt < :cutoff")
    int deleteFullBefore(@Param("cutoff") Instant cutoff);
}
