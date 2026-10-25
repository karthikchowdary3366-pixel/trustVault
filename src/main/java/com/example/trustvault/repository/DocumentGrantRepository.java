package com.example.trustvault.repository;

import com.example.trustvault.model.DocumentGrant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentGrantRepository extends JpaRepository<DocumentGrant, String> {
	Optional<DocumentGrant> findByDocumentIdAndGranteeUsername(String documentId, String username);
	List<DocumentGrant> findAllByDocumentIdOrderByGranteeUsernameAsc(String documentId);
	List<DocumentGrant> findAllByGranteeUsername(String username);
	void deleteByDocumentId(String documentId);

	@Modifying
	@Query("delete from DocumentGrant g where g.grantee.username = :username")
	void deleteAllByGranteeUsername(@Param("username") String username);
}
