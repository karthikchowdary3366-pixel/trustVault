package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.DocumentGrantRepository;
import com.example.trustvault.repository.PasswordResetChallengeRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserService {
	private final AppUserRepository users;
	private final AuditService audit;
	private final PasswordEncoder passwordEncoder;
	private final VaultDocumentRepository documents;
	private final DocumentGrantRepository grants;
	private final PasswordResetChallengeRepository resetChallenges;

	public AdminUserService(AppUserRepository users, AuditService audit, PasswordEncoder passwordEncoder,
			VaultDocumentRepository documents, DocumentGrantRepository grants,
			PasswordResetChallengeRepository resetChallenges) {
		this.users = users;
		this.audit = audit;
		this.passwordEncoder = passwordEncoder;
		this.documents = documents;
		this.grants = grants;
		this.resetChallenges = resetChallenges;
	}

	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public void createUser(String actor, String username, String fullName, String email, String password, Role role) {
		String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		if (users.existsById(normalizedUsername)) {
			throw new AccountAlreadyExistsException("That username is already in use.");
		}
		if (users.existsByEmail(normalizedEmail)) {
			throw new AccountAlreadyExistsException("That email address is already registered.");
		}
		if (role != Role.VIEWER && role != Role.OWNER) {
			throw new IllegalArgumentException("Administrators can create Viewer or Owner accounts only.");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length < 12
				|| password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new IllegalArgumentException("Password must be 12–72 UTF-8 bytes.");
		}
		AppUser user = new AppUser(normalizedUsername, passwordEncoder.encode(password), fullName.trim(),
				normalizedEmail, role);
		user.requirePasswordChange();
		users.save(user);
		audit.recordSuccess(actor, "USER_CREATE", null,
				"Created " + role + " account " + normalizedUsername);
	}

	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public void demoteOwnerToViewer(String actor, String username) {
		AppUser user = users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
		if (user.getRole() != Role.OWNER) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only owner accounts can be demoted to viewer.");
		}
		user.changeRole(Role.VIEWER);
		audit.recordSuccess(actor, "ROLE_CHANGE", null,
				"Demoted " + user.getUsername() + " from OWNER to VIEWER");
	}

	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public void setAccountActive(String actor, String username, boolean active) {
		AppUser user = users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
		if (user.getUsername().equals(actor) && !active) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot deactivate your own administrator account.");
		}
		if (user.getRole() == Role.ADMIN && !active && user.isActive()
				&& users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "The last active administrator account cannot be deactivated.");
		}
		if (user.isActive() == active) {
			return;
		}
		user.setActive(active);
		audit.recordSuccess(actor, "USER_STATUS", null,
				(active ? "Reactivated " : "Deactivated ") + user.getUsername());
	}

	@PreAuthorize("hasRole('ADMIN')")
	@Transactional
	public void deleteUser(String actor, String username, String transferToUsername) {
		AppUser user = users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
		if (user.getUsername().equals(actor)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot delete your own administrator account.");
		}
		if (user.isActive() && user.getRole() == Role.ADMIN
				&& users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "The last active administrator account cannot be deleted.");
		}

		List<VaultDocument> ownedDocuments = documents.findAllByOwnerUsername(username);
		AppUser transferTarget = null;
		if (transferToUsername != null && !transferToUsername.isBlank()) {
			transferTarget = users.findById(transferToUsername).orElseThrow(
					() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a valid document owner."));
			if (transferTarget == user || transferTarget.getRole() != Role.OWNER || !transferTarget.isActive()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Documents can only be transferred to a different active account with the Owner role.");
			}
		}
		if (!ownedDocuments.isEmpty() && transferTarget == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Select an Owner account to receive this user's documents.");
		}
		if (transferTarget != null) {
			for (VaultDocument document : ownedDocuments) {
				document.changeOwner(transferTarget);
			}
			documents.saveAll(ownedDocuments);
		}

		grants.deleteAllByGranteeUsername(username);
		resetChallenges.deleteById(username);
		users.delete(user);
		String transferDetails = transferTarget == null ? ""
				: "; transferred " + ownedDocuments.size() + " document(s) to " + transferTarget.getUsername();
		audit.recordSuccess(actor, "USER_DELETE", null,
				"Deleted account " + username + transferDetails);
	}

	public static class AccountAlreadyExistsException extends RuntimeException {
		public AccountAlreadyExistsException(String message) {
			super(message);
		}
	}
}
