package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "account_email_verification_challenges")
public class AccountEmailVerificationChallenge {
	@Id
	@Column(length = 80)
	private String username;

	@Column(nullable = false, length = 64)
	private String codeHash;

	@Column(length = 254)
	private String email;

	@Column(nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private Instant sentAt;

	@Column(nullable = false)
	private int failedAttempts;

	protected AccountEmailVerificationChallenge() {
	}

	public AccountEmailVerificationChallenge(String username, String codeHash, String email, Instant expiresAt,
			Instant sentAt) {
		this.username = username;
		this.codeHash = codeHash;
		this.email = email;
		this.expiresAt = expiresAt;
		this.sentAt = sentAt;
	}

	public String getEmail() {
		return email;
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

	public int getFailedAttempts() {
		return failedAttempts;
	}

	public void reset(String codeHash, String email, Instant expiresAt, Instant sentAt) {
		this.codeHash = codeHash;
		this.email = email;
		this.expiresAt = expiresAt;
		this.sentAt = sentAt;
		this.failedAttempts = 0;
	}

	public void recordFailedAttempt() {
		failedAttempts++;
	}
}
