package com.example.trustvault.repository;

import com.example.trustvault.model.RegistrationChallenge;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationChallengeRepository extends JpaRepository<RegistrationChallenge, String> {
	boolean existsByUsernameAndEmailNot(String username, String email);
	long deleteByExpiresAtBefore(Instant instant);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select challenge from RegistrationChallenge challenge where challenge.email = :email")
	Optional<RegistrationChallenge> lockByEmail(@Param("email") String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select challenge from RegistrationChallenge challenge where challenge.username = :username")
	Optional<RegistrationChallenge> lockByUsername(@Param("username") String username);
}
