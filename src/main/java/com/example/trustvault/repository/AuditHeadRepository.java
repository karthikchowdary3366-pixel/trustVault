package com.example.trustvault.repository;

import com.example.trustvault.model.AuditHead;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AuditHeadRepository extends JpaRepository<AuditHead, Integer> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select head from AuditHead head where head.id = 1")
	Optional<AuditHead> lockHead();
}
