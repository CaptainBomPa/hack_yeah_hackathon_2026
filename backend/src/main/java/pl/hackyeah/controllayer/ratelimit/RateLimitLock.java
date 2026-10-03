package pl.hackyeah.controllayer.ratelimit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rate_limit_lock")
public class RateLimitLock {
    @Id
    private Integer id;

    protected RateLimitLock() {}
}
