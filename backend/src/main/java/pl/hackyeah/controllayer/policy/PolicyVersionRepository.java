package pl.hackyeah.controllayer.policy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    Optional<PolicyVersion> findTopByOrderByVersionDesc();

    List<PolicyVersion> findAllByOrderByVersionDesc(Pageable pageable);
}
