package pl.hackyeah.controllayer.ratelimit;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface RateLimitLockRepository extends JpaRepository<RateLimitLock, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"),
            @QueryHint(name = "jakarta.persistence.query.timeout", value = "3000")})
    @Query("select l from RateLimitLock l where l.id = 1")
    Optional<RateLimitLock> lockGlobal();
}
