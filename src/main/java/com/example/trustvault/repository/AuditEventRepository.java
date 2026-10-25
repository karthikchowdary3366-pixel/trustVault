package com.example.trustvault.repository;

import com.example.trustvault.model.AuditEvent;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
	Optional<AuditEvent> findTopByOrderByIdDesc();
	List<AuditEvent> findAllByOrderByIdDesc();
	List<AuditEvent> findAllByOrderByIdAsc();

	@Query("select e.documentId from AuditEvent e where e.actor = :actor and e.allowed = true "
			+ "and e.action in :actions and e.documentId is not null")
	List<String> findDocumentIdsByActorAndActions(@Param("actor") String actor,
			@Param("actions") Collection<String> actions);
}
