package com.example.trustvault.repository;

import com.example.trustvault.model.AuditEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
	Optional<AuditEvent> findTopByOrderByIdDesc();
	List<AuditEvent> findAllByOrderByIdDesc();
	List<AuditEvent> findAllByOrderByIdAsc();
}
