package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class AppUser {
	@Id
	@Column(length = 80)
	private String username;

	@Column(nullable = false)
	private String passwordHash;

	@Column(length = 120)
	private String fullName;

	@Column(length = 254)
	private String email;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean emailVerified;

	private Instant termsAcceptedAt;

	@Column(length = 32)
	private String termsVersion;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean passwordChangeRequired;

	@Column(nullable = false, columnDefinition = "boolean default true")
	private boolean active = true;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	protected AppUser() {
	}

	public AppUser(String username, String passwordHash, String fullName, String email, Role role) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.fullName = fullName;
		this.email = email;
		this.role = role;
	}

	public AppUser(String username, String passwordHash, String fullName, String email, Role role,
			Instant termsAcceptedAt, String termsVersion) {
		this(username, passwordHash, fullName, email, role);
		this.emailVerified = termsAcceptedAt != null;
		this.termsAcceptedAt = termsAcceptedAt;
		this.termsVersion = termsVersion;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getFullName() {
		return fullName;
	}

	public String getEmail() {
		return email;
	}

	public boolean isEmailVerified() {
		return emailVerified || termsAcceptedAt != null;
	}

	public Instant getTermsAcceptedAt() {
		return termsAcceptedAt;
	}

	public String getTermsVersion() {
		return termsVersion;
	}

	public Role getRole() {
		return role;
	}

	public boolean isPasswordChangeRequired() {
		return passwordChangeRequired;
	}

	public boolean isActive() {
		return active;
	}

	public void requirePasswordChange() {
		this.passwordChangeRequired = true;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public void setFullName(String fullName) {
		this.fullName = fullName;
	}

	public void setEmailVerified(boolean emailVerified) {
		this.emailVerified = emailVerified;
	}

	public void changeEmail(String email) {
		this.email = email;
	}

	public AppUser renamed(String username) {
		AppUser renamed = new AppUser(username, passwordHash, fullName, email, role, termsAcceptedAt, termsVersion);
		renamed.passwordChangeRequired = passwordChangeRequired;
		renamed.active = active;
		renamed.emailVerified = isEmailVerified();
		return renamed;
	}

	public void changeRole(Role role) {
		this.role = role;
	}

	public void changePasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
		this.passwordChangeRequired = false;
	}
}
