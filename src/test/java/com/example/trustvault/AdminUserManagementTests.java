package com.example.trustvault;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.AuditEvent;
import com.example.trustvault.model.DocumentGrant;
import com.example.trustvault.model.DocumentPermission;
import com.example.trustvault.model.Role;
import com.example.trustvault.model.VaultDocument;
import com.example.trustvault.repository.AccountEmailVerificationChallengeRepository;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.AuditEventRepository;
import com.example.trustvault.repository.DocumentGrantRepository;
import com.example.trustvault.repository.VaultDocumentRepository;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserManagementTests {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AccountEmailVerificationChallengeRepository emailChallenges;

	@Autowired
	private AuditEventRepository auditEvents;

	@Autowired
	private VaultDocumentRepository documents;

	@Autowired
	private DocumentGrantRepository grants;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private UserDetailsService userDetailsService;

	@MockitoBean
	private JavaMailSender mailSender;

	@AfterEach
	void restoreDemoRoles() {
		grants.deleteById("admin-added-grant");
		documents.deleteById("admin-added-document");
		grants.deleteById("rename-grant");
		documents.deleteById("rename-document");
		users.deleteById("admin-added");
		users.deleteById("delete-me");
		users.deleteById("rename-me");
		users.deleteById("renamed-user");
		users.deleteById("new-owner");
		users.deleteById("email-later");
		emailChallenges.deleteById("admin");
		emailChallenges.deleteById("email-later");
		users.findById("viewer").ifPresent(viewer -> {
			viewer.setActive(true);
			users.save(viewer);
		});
		users.findById("viewer").ifPresent(viewer -> {
			viewer.changeRole(Role.VIEWER);
			users.save(viewer);
		});
		users.findById("owner").ifPresent(owner -> {
			owner.changeRole(Role.OWNER);
			users.save(owner);
		});
		users.findById("admin").ifPresent(admin -> {
			admin.setFullName(null);
			admin.setEmailVerified(false);
			users.save(admin);
		});
	}

	@Test
	void administratorCanCreateViewerOrOwnerButNotAdministratorAccounts() throws Exception {
		mvc.perform(post("/admin/users")
				.param("fullName", "New Owner")
				.param("email", "NEW-OWNER@example.test")
				.param("username", "New-Owner")
				.param("password", "NewOwnerPassword!234")
				.param("role", "OWNER")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/admin/users/new-owner"));

		AppUser created = users.findById("new-owner").orElseThrow();
		assertEquals(Role.OWNER, created.getRole());
		assertEquals("new-owner@example.test", created.getEmail());
		assertTrue(created.getPasswordHash() != null
				&& !created.getPasswordHash().equals("NewOwnerPassword!234"));
		assertTrue(created.isPasswordChangeRequired());
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("new-owner").roles("OWNER")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("change your initial password")));

		mvc.perform(post("/account/password")
				.param("currentPassword", "WrongInitialPassword!234")
				.param("newPassword", "UpdatedPassword!987")
				.param("confirmPassword", "UpdatedPassword!987")
				.with(SecurityMockMvcRequestPostProcessors.user("new-owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/account/settings"));
		assertTrue(users.findById("new-owner").orElseThrow().isPasswordChangeRequired());

		mvc.perform(post("/account/password")
				.param("currentPassword", "NewOwnerPassword!234")
				.param("newPassword", "UpdatedPassword!987")
				.param("confirmPassword", "UpdatedPassword!987")
				.with(SecurityMockMvcRequestPostProcessors.user("new-owner").roles("OWNER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/account/settings"));
		AppUser updated = users.findById("new-owner").orElseThrow();
		assertFalse(updated.isPasswordChangeRequired());
		assertTrue(passwordEncoder.matches("UpdatedPassword!987", updated.getPasswordHash()));
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "PASSWORD_CHANGE".equals(event.getAction())
						&& "new-owner".equals(event.getActor())
						&& event.isAllowed()));

		mvc.perform(post("/admin/users")
				.param("fullName", "Not Admin")
				.param("email", "not-admin@example.test")
				.param("username", "not-admin")
				.param("password", "NotAdminPassword!234")
				.param("role", "ADMIN")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isOk());
		assertTrue(users.findById("not-admin").isEmpty());
	}

	@Test
	void administratorCanDeleteUserAfterTransferringOwnedDocuments() throws Exception {
		mvc.perform(post("/admin/users")
				.param("fullName", "Temporary Owner")
				.param("email", "temporary-owner@example.test")
				.param("username", "admin-added")
				.param("password", "TemporaryOwner!234")
				.param("role", "OWNER")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		AppUser account = users.findById("admin-added").orElseThrow();
		VaultDocument document = documents.saveAndFlush(new VaultDocument("admin-added-document", "Important",
				"General", null, "admin-added-document", "application/octet-stream", 1, Instant.now(), account));
		grants.saveAndFlush(new DocumentGrant("admin-added-grant", document, account, DocumentPermission.READ,
				Instant.now()));

		mvc.perform(post("/admin/users/admin-added/delete")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());
		assertTrue(users.findById("admin-added").isPresent());

		mvc.perform(post("/admin/users/admin-added/delete")
				.param("transferToUsername", "owner")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/admin/users"));

		assertTrue(users.findById("admin-added").isEmpty());
		assertEquals("owner", documents.findById("admin-added-document").orElseThrow().getOwner().getUsername());
		assertTrue(grants.findById("admin-added-grant").isEmpty());
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "USER_DELETE".equals(event.getAction())
						&& "admin".equals(event.getActor())
						&& event.getDetails().contains("transferred 1 document(s) to owner")
						&& event.isAllowed()));
	}

	@Test
	void administratorCanDeleteUserWithoutOwnedDocuments() throws Exception {
		users.saveAndFlush(new AppUser("delete-me", passwordEncoder.encode("DeleteMePassword!234"),
				"Delete Me", "delete-me@example.test", Role.VIEWER));

		mvc.perform(get("/admin/users/delete-me")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Delete user")));

		mvc.perform(post("/admin/users/delete-me/delete")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/admin/users"));

		assertTrue(users.findById("delete-me").isEmpty());
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "USER_DELETE".equals(event.getAction())
						&& "admin".equals(event.getActor())
						&& event.getDetails().contains("Deleted account delete-me")
						&& event.isAllowed()));
	}

	@Test
	void administratorCannotPromoteViewerToOwner() throws Exception {
		mvc.perform(post("/admin/users/viewer/promote")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isNotFound());

		assertEquals(Role.VIEWER, users.findById("viewer").orElseThrow().getRole());
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Upload a document"))));
	}

	@Test
	void administratorCanDemoteOwnerAndChangeIsAudited() throws Exception {
		mvc.perform(get("/admin/users")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("User details")))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Add a user"))));

		mvc.perform(get("/admin/users/new")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Add user")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Create user")));

		mvc.perform(get("/admin/users/owner")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("owner@example.test")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Change to Viewer")))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("passwordHash"))));

		mvc.perform(post("/admin/users/owner/demote")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		assertEquals(Role.VIEWER, users.findById("owner").orElseThrow().getRole());
		List<AuditEvent> roleEvents = auditEvents.findAllByOrderByIdDesc().stream()
				.filter(event -> "ROLE_CHANGE".equals(event.getAction())
						&& "admin".equals(event.getActor())
						&& event.getDetails().contains("owner")
						&& event.isAllowed())
				.toList();
		assertTrue(roleEvents.stream().anyMatch(event -> event.getDetails().contains("OWNER to VIEWER")));
	}

	@Test
	void accountDeactivationBlocksExistingSessionsAndCanBeReversed() throws Exception {
		mvc.perform(post("/admin/users/viewer/deactivate")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		assertFalse(users.findById("viewer").orElseThrow().isActive());
		assertFalse(userDetailsService.loadUserByUsername("viewer").isEnabled());
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login?disabled"));
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "ACCOUNT_ACCESS".equals(event.getAction())
						&& "viewer".equals(event.getActor())
						&& !event.isAllowed()));

		mvc.perform(post("/admin/users/viewer/activate")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		assertTrue(users.findById("viewer").orElseThrow().isActive());
		mvc.perform(get("/documents")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isOk());
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "USER_STATUS".equals(event.getAction())
						&& "admin".equals(event.getActor())
						&& event.getDetails().contains("Deactivated viewer")
						&& event.isAllowed()));
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "USER_STATUS".equals(event.getAction())
						&& "admin".equals(event.getActor())
						&& event.getDetails().contains("Reactivated viewer")
						&& event.isAllowed()));
	}

	@Test
	void accountSettingsShowMissingProfileTasksAndRequireEmailVerification() throws Exception {
		mvc.perform(get("/account/settings")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Add your full name")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Verify your email")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Change username")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Change password")));

		mvc.perform(post("/account/settings/full-name")
				.param("fullName", "TrustVault Administrator")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());
		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/account/settings/email-verification")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());
		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		Matcher codeMatcher = Pattern.compile("code is ([0-9]{6})").matcher(mail.getValue().getText());
		assertTrue(codeMatcher.find());
		mvc.perform(post("/account/settings/email-verification/confirm")
				.param("code", codeMatcher.group(1))
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		mvc.perform(get("/account/settings")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Add your full name"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Verify your email"))))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Email status</dt><dd>Verified")));
	}

	@Test
	void userCanAddAndVerifyEmailAfterRegistration() throws Exception {
		users.saveAndFlush(new AppUser("email-later", passwordEncoder.encode("EmailLaterPassword!234"),
				"Email Later", null, Role.VIEWER));

		mvc.perform(get("/account/settings")
				.with(SecurityMockMvcRequestPostProcessors.user("email-later").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Add email and send code")))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Contact an administrator"))));

		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/account/settings/email-verification")
				.param("email", "Email-Later@example.test")
				.with(SecurityMockMvcRequestPostProcessors.user("email-later").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());
		AppUser accountWithEmail = users.findById("email-later").orElseThrow();
		assertEquals("email-later@example.test", accountWithEmail.getEmail());
		assertFalse(accountWithEmail.isEmailVerified());

		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		assertEquals("email-later@example.test", mail.getValue().getTo()[0]);
		Matcher codeMatcher = Pattern.compile("code is ([0-9]{6})").matcher(mail.getValue().getText());
		assertTrue(codeMatcher.find());

		mvc.perform(post("/account/settings/email-verification/confirm")
				.param("code", codeMatcher.group(1))
				.with(SecurityMockMvcRequestPostProcessors.user("email-later").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		assertTrue(users.findById("email-later").orElseThrow().isEmailVerified());
		mvc.perform(get("/account/settings")
				.with(SecurityMockMvcRequestPostProcessors.user("email-later").roles("VIEWER")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Verify your email"))));
	}

	@Test
	void changingUsernameMovesOwnedDocumentsAndReceivedGrantsAndRequiresNewLogin() throws Exception {
		AppUser original = users.saveAndFlush(new AppUser("rename-me", passwordEncoder.encode("RenameMePassword!234"),
				"Rename Me", "rename-me@example.test", Role.VIEWER));
		VaultDocument document = documents.saveAndFlush(new VaultDocument("rename-document", "Shared item", "General",
				null, "rename-document", "application/octet-stream", 1, Instant.now(),
				original));
		grants.saveAndFlush(new DocumentGrant("rename-grant", document, original, DocumentPermission.READ,
				Instant.now()));

		mvc.perform(post("/account/settings/username")
				.param("newUsername", "Renamed-User")
				.param("currentPassword", "IncorrectPassword!234")
				.with(SecurityMockMvcRequestPostProcessors.user("rename-me").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/account/settings"));
		assertTrue(users.findById("rename-me").isPresent());

		mvc.perform(post("/account/settings/username")
				.param("newUsername", "Renamed-User")
				.param("currentPassword", "RenameMePassword!234")
				.with(SecurityMockMvcRequestPostProcessors.user("rename-me").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/login?usernameChanged"));

		assertTrue(users.findById("rename-me").isEmpty());
		assertEquals("renamed-user", documents.findById("rename-document").orElseThrow().getOwner().getUsername());
		assertEquals("renamed-user", grants.findById("rename-grant").orElseThrow().getGrantee().getUsername());
		assertEquals(Role.VIEWER, users.findById("renamed-user").orElseThrow().getRole());
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "USERNAME_CHANGE".equals(event.getAction())
						&& "rename-me".equals(event.getActor())
						&& event.getDetails().contains("renamed-user")
						&& event.isAllowed()));
	}

	@Test
	void onlyAdministratorsCanSeeOrUseRoleManagement() throws Exception {
		mvc.perform(get("/admin/users")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden());

		mvc.perform(get("/admin/users/owner")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
				.andExpect(status().isForbidden());

		mvc.perform(get("/admin/users/missing-user")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
				.andExpect(status().isNotFound());

		mvc.perform(post("/admin/users/owner/demote")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isForbidden());

		mvc.perform(post("/admin/users/owner/delete")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isForbidden());

		mvc.perform(post("/admin/users")
				.param("fullName", "Unauthorized")
				.param("email", "unauthorized@example.test")
				.param("username", "unauthorized")
				.param("password", "UnauthorizedPassword!234")
				.param("role", "VIEWER")
				.with(SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().isForbidden());

		assertEquals(Role.VIEWER, users.findById("viewer").orElseThrow().getRole());
		assertEquals(Role.OWNER, users.findById("owner").orElseThrow().getRole());
		assertTrue(users.findById("owner").isPresent());
		assertTrue(users.findById("unauthorized").isEmpty());
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "viewer".equals(event.getActor())
						&& "ROLE_CHANGE".equals(event.getAction())
						&& !event.isAllowed()));
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "viewer".equals(event.getActor())
						&& "USER_CREATE".equals(event.getAction())
						&& !event.isAllowed()));
		assertTrue(auditEvents.findAllByOrderByIdDesc().stream()
				.anyMatch(event -> "viewer".equals(event.getActor())
						&& "USER_DELETE".equals(event.getAction())
						&& !event.isAllowed()));
	}

	@Test
	void administratorCannotDeleteTheirOwnAccount() throws Exception {
		mvc.perform(post("/admin/users/admin/delete")
				.with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
				.andExpect(status().is3xxRedirection());

		assertEquals(Role.ADMIN, users.findById("admin").orElseThrow().getRole());
	}

	@Test
	void ownersCannotManageAdministratorsFromTheWeb() throws Exception {
		AppUser admin = users.findById("admin").orElseThrow();
		assertEquals(Role.ADMIN, admin.getRole());
		assertEquals(Role.VIEWER, users.findById("viewer").orElseThrow().getRole());
		assertEquals(Role.OWNER, users.findById("owner").orElseThrow().getRole());
	}
}
