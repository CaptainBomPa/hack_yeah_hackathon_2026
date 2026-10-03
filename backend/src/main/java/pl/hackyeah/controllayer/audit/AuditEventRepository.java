package pl.hackyeah.controllayer.audit;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    Optional<AuditEvent> findTopByOrderBySeqDesc();

    Optional<AuditEvent> findByRequestId(String requestId);
}
