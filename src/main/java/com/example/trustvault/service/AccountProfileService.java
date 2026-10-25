package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.DocumentGrant;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AccountEmailVerificationChallengeRepository;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.DocumentGrantRepository;
import com.example.trustvault.repository.PasswordResetChallengeRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountProfileService {
	private final AppUserRepository users;
	private final DocumentGrantRepository grants;
	private final VaultDocumentRepository documents;
	private final PasswordResetChallengeRepository passwordResetChallenges;
	private final AccountEmailVerificationChallengeRepository emailChallenges;
	private final AuditService audit;
	private final PasswordEncoder passwordEncoder;

	public AccountProfileService(AppUserRepository users, DocumentGrantRepository grants,
			VaultDocumentRepository documents, PasswordResetChallengeRepository passwordResetChallenges,
			AccountEmailVerificationChallengeRepository emailChallenges, AuditService audit,
			PasswordEncoder passwordEncoder) {
		this.users = users;
		this.grants = grants;
		this.documents = documents;
		this.passwordResetChallenges = passwordResetChallenges;
		this.emailChallenges = emailChallenges;
		this.audit = audit;
		this.passwordEncoder = passwordEncoder;
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional
	public void addFullName(String username, String fullName) {
		AppUser user = requireAccount(username);
		if (fullName == null || fullName.isBlank() || fullName.strip().length() > 120) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a name up to 120 characters.");
		}
		if (user.getFullName() != null && !user.getFullName().isBlank()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A full name is already set.");
		}
		user.setFullName(fullName.strip());
		audit.recordSuccess(username, "PROFILE_UPDATE", null, "Full name added");
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional
	public String changeUsername(String currentUsername, String submittedUsername, String currentPassword) {
		String updatedUsername = submittedUsername == null ? ""
				: submittedUsername.strip().toLowerCase(Locale.ROOT);
		if (!updatedUsername.matches("[a-z0-9][a-z0-9._-]{2,79}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Username must be 3–80 letters, numbers, dots, underscores, or hyphens.");
		}
		AppUser current = users.lockByUsername(currentUsername).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		if (currentPassword == null || !passwordEncoder.matches(currentPassword, current.getPasswordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
		}
		if (currentUsername.equals(updatedUsername)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different username.");
		}
		if (users.existsById(updatedUsername)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already in use.");
		}

		List<VaultDocument> owned = documents.findAllByOwnerUsername(currentUsername);
		List<DocumentGrant> receivedGrants = grants.findAllByGranteeUsername(currentUsername);
		AppUser updated = current.renamed(updatedUsername);
		current.changeEmail(null);
		users.saveAndFlush(current);
		users.saveAndFlush(updated);
		for (VaultDocument document : owned) {
			document.changeOwner(updated);
		}
		for (DocumentGrant grant : receivedGrants) {
			grant.changeGrantee(updated);
		}
		documents.saveAll(owned);
		grants.saveAll(receivedGrants);
		passwordResetChallenges.deleteById(currentUsername);
		emailChallenges.deleteById(currentUsername);
		users.delete(current);
		audit.recordSuccess(currentUsername, "USERNAME_CHANGE", null,
				"Username changed to " + updatedUsername);
		return updatedUsername;
	}

	private AppUser requireAccount(String username) {
		return users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
	}
}
