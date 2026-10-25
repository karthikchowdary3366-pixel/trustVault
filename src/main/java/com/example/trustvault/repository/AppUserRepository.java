package com.example.trustvault.repository;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
	List<AppUser> findAllByOrderByUsernameAsc();
	List<AppUser> findAllByActiveTrueOrderByUsernameAsc();
	List<AppUser> findAllByRoleOrderByUsernameAsc(Role role);
	List<AppUser> findAllByRoleAndActiveTrueOrderByUsernameAsc(Role role);
	boolean existsByEmail(String email);
	long countByRoleAndActiveTrue(Role role);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select user from AppUser user where user.username = :username")
	Optional<AppUser> lockByUsername(@Param("username") String username);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select user from AppUser user where user.email = :email")
	Optional<AppUser> lockByEmail(@Param("email") String email);
}
