package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "registration_challenges")
public class RegistrationChallenge {
	@Id
	@Column(length = 254)
	private String email;

	@Column(nullable = false, length = 80)
	private String username;

	@Column(nullable = false, length = 120)
	private String fullName;

	@Column(nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 64)
	private String codeHash;

	@Column(nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private Instant sentAt;

	@Column(nullable = false)
	private Instant termsAcceptedAt;

	@Column(nullable = false, length = 32)
	private String termsVersion;

	@Column(nullable = false)
	private int failedAttempts;

	protected RegistrationChallenge() {
	}

	public RegistrationChallenge(String email, String username, String fullName, String passwordHash,
			String codeHash, Instant expiresAt, Instant sentAt, Instant termsAcceptedAt, String termsVersion) {
		this.email = email;
		this.username = username;
		this.fullName = fullName;
		this.passwordHash = passwordHash;
		this.codeHash = codeHash;
		this.expiresAt = expiresAt;
		this.sentAt = sentAt;
		this.termsAcceptedAt = termsAcceptedAt;
		this.termsVersion = termsVersion;
	}

	public String getEmail() {
		return email;
	}

	public String getUsername() {
		return username;
	}

	public String getFullName() {
		return fullName;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getCodeHash() {
		return codeHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public Instant getTermsAcceptedAt() {
		return termsAcceptedAt;
	}

	public String getTermsVersion() {
		return termsVersion;
	}

	public int getFailedAttempts() {
		return failedAttempts;
	}

	public void update(String username, String fullName, String passwordHash, String codeHash,
			Instant expiresAt, Instant sentAt, Instant termsAcceptedAt, String termsVersion) {
		this.username = username;
		this.fullName = fullName;
		this.passwordHash = passwordHash;
		this.codeHash = codeHash;
		this.expiresAt = expiresAt;
		this.sentAt = sentAt;
		this.termsAcceptedAt = termsAcceptedAt;
		this.termsVersion = termsVersion;
		this.failedAttempts = 0;
	}

	public void recordFailedAttempt() {
		failedAttempts++;
	}
}
