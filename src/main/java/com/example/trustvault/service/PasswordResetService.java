package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.PasswordResetChallenge;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.PasswordResetChallengeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
	private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
	private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(1);
	private static final int MAX_ATTEMPTS = 5;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AppUserRepository users;
	private final PasswordResetChallengeRepository challenges;
	private final AuditService audit;
	private final PasswordEncoder passwordEncoder;
	private final JavaMailSender mailSender;
	private final byte[] signingKey;
	private final String fromAddress;
	private final boolean enabled;

	public PasswordResetService(AppUserRepository users, PasswordResetChallengeRepository challenges,
			PasswordEncoder passwordEncoder, JavaMailSender mailSender, AuditService audit,
			@Value("${trustvault.password-reset.key}") String signingKey,
			@Value("${trustvault.password-reset.from}") String fromAddress,
			@Value("${trustvault.password-reset.enabled:false}") boolean enabled) {
		if (enabled && signingKey.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("Password reset HMAC key must be at least 32 UTF-8 bytes.");
		}
		this.users = users;
		this.challenges = challenges;
		this.audit = audit;
		this.passwordEncoder = passwordEncoder;
		this.mailSender = mailSender;
		this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
		this.fromAddress = fromAddress;
		this.enabled = enabled;
	}

	@Transactional
	public void requestCode(String submittedEmail) {
		if (!enabled) {
			throw new PasswordResetUnavailableException();
		}
		String email = normalizeEmail(submittedEmail);
		if (email.isEmpty() || email.length() > 254) {
			return;
		}
		AppUser account = users.lockByEmail(email).orElse(null);
		if (account == null || account.getEmail() == null || account.getEmail().isBlank()) {
			return;
		}
		String username = account.getUsername();

		Instant now = Instant.now();
		PasswordResetChallenge existing = challenges.lockByUsername(username).orElse(null);
		if (existing != null && existing.getSentAt().plus(REQUEST_COOLDOWN).isAfter(now)) {
			return;
		}

		String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(account.getEmail());
		message.setSubject("TrustVault password reset code");
		message.setText("Your TrustVault password reset code is " + code
				+ ". It expires in 10 minutes. If you did not request a reset, ignore this email.");
		mailSender.send(message);

		challenges.save(new PasswordResetChallenge(username, hashCode(username, code),
				now.plus(CODE_LIFETIME), now));
		audit.recordSuccess(username, "PASSWORD_RESET_OTP_SENT", null,
				"Password reset verification code sent");
	}

	@Transactional
	public ResetResult resetPassword(String submittedEmail, String code, String newPassword) {
		if (!enabled) {
			throw new PasswordResetUnavailableException();
		}
		String email = normalizeEmail(submittedEmail);
		if (email.isEmpty() || email.length() > 254) {
			return ResetResult.INVALID_CODE;
		}
		AppUser account = users.lockByEmail(email).orElse(null);
		if (account == null) {
			return ResetResult.INVALID_CODE;
		}
		String username = account.getUsername();
		PasswordResetChallenge challenge = challenges.lockByUsername(username).orElse(null);
		if (challenge == null) {
			return ResetResult.INVALID_CODE;
		}
		if (!challenge.getExpiresAt().isAfter(Instant.now())
				|| challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
			challenges.delete(challenge);
			return ResetResult.INVALID_CODE;
		}
		if (code == null || !code.trim().matches("[0-9]{6}") || !MessageDigest.isEqual(
				hashCode(username, code.trim()).getBytes(StandardCharsets.UTF_8),
				challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
			challenge.recordFailedAttempt();
			if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
				challenges.delete(challenge);
			}
			audit.recordDenied(username, "PASSWORD_RESET_OTP", null,
					"Invalid or expired password reset code submitted");
			return ResetResult.INVALID_CODE;
		}
		if (newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length < 12
				|| newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
			return ResetResult.INVALID_PASSWORD;
		}

		account.changePasswordHash(passwordEncoder.encode(newPassword));
		challenges.delete(challenge);
		audit.recordSuccess(username, "PASSWORD_RESET", null, "Password reset using email verification code");
		return ResetResult.SUCCESS;
	}

	private String hashCode(String username, String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal((username + "\n" + code).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to protect password reset code", exception);
		}
	}

	private String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	public enum ResetResult {
		SUCCESS,
		INVALID_CODE,
		INVALID_PASSWORD
	}

	public static class PasswordResetUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
