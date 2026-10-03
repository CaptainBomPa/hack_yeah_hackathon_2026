package pl.hackyeah.controllayer.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    Optional<AuditEvent> findTopByOrderBySeqDesc();

    Optional<AuditEvent> findByRequestId(String requestId);

    // Wartości do list filtrów (GET /api/audit/facets).

    @Query("select distinct e.action from AuditEvent e order by e.action")
    List<String> distinctActions();

    @Query("select distinct e.principal from AuditEvent e where e.principal is not null order by e.principal")
    List<String> distinctPrincipals();

    @Query("select distinct e.model from AuditEvent e where e.model is not null order by e.model")
    List<String> distinctModels();

    @Query("select distinct e.blockedBy from AuditEvent e where e.blockedBy is not null order by e.blockedBy")
    List<String> distinctBlockedBy();
}
