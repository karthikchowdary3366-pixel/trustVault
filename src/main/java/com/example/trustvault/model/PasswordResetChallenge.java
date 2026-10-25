package com.example.trustvault.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "password_reset_challenges")
public class PasswordResetChallenge {
	@Id
	@Column(length = 80)
	private String username;

	@Column(nullable = false, length = 64)
	private String codeHash;

	@Column(nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private Instant sentAt;

	@Column(nullable = false)
	private int failedAttempts;

	protected PasswordResetChallenge() {
	}

	public PasswordResetChallenge(String username, String codeHash, Instant expiresAt, Instant sentAt) {
		this.username = username;
		this.codeHash = codeHash;
		this.expiresAt = expiresAt;
		this.sentAt = sentAt;
		this.failedAttempts = 0;
	}

	public String getUsername() {
		return username;
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

	public void recordFailedAttempt() {
		failedAttempts++;
	}
}
