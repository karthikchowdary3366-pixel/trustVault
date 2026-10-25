package com.example.trustvault.repository;

import com.example.trustvault.model.AccountEmailVerificationChallenge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountEmailVerificationChallengeRepository
		extends JpaRepository<AccountEmailVerificationChallenge, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select challenge from AccountEmailVerificationChallenge challenge where challenge.username = :username")
	Optional<AccountEmailVerificationChallenge> lockByUsername(@Param("username") String username);
}
