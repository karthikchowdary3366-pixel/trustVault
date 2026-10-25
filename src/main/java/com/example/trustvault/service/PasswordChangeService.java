package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.repository.AppUserRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordChangeService {
	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;

	public PasswordChangeService(AppUserRepository users, PasswordEncoder passwordEncoder, AuditService audit) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
	}

	@Transactional
	public ChangeResult changePassword(String username, String currentPassword, String newPassword) {
		AppUser user = users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			return ChangeResult.INVALID_CURRENT_PASSWORD;
		}
		if (newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length < 12
				|| newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
			return ChangeResult.INVALID_PASSWORD;
		}
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			return ChangeResult.SAME_PASSWORD;
		}

		user.changePasswordHash(passwordEncoder.encode(newPassword));
		audit.recordSuccess(username, "PASSWORD_CHANGE", null, "Password changed by the account holder");
		return ChangeResult.SUCCESS;
	}

	public enum ChangeResult {
		SUCCESS,
		INVALID_CURRENT_PASSWORD,
		INVALID_PASSWORD,
		SAME_PASSWORD
	}
}
