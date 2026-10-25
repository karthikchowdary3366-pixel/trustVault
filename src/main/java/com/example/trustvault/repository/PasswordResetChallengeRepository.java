package com.example.trustvault.repository;

import com.example.trustvault.model.PasswordResetChallenge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetChallengeRepository extends JpaRepository<PasswordResetChallenge, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select challenge from PasswordResetChallenge challenge where challenge.username = :username")
	Optional<PasswordResetChallenge> lockByUsername(@Param("username") String username);
}
