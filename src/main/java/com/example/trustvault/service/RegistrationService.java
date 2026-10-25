package com.example.trustvault.service;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.RegistrationChallenge;
import com.example.trustvault.model.Role;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.RegistrationChallengeRepository;
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
import org.springframework.core.env.Environment;

@Service
public class RegistrationService {
	private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
	private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(1);
	private static final int MAX_ATTEMPTS = 5;
	private static final String TERMS_VERSION = "2026-10-23";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AppUserRepository users;
	private final RegistrationChallengeRepository challenges;
	private final PasswordEncoder passwordEncoder;
	private final JavaMailSender mailSender;
	private final AuditService audit;
	private final byte[] signingKey;
	private final String fromAddress;
	private final boolean enabled;
	private final boolean localOtpEnabled;

	public RegistrationService(AppUserRepository users, RegistrationChallengeRepository challenges,
			PasswordEncoder passwordEncoder, JavaMailSender mailSender, AuditService audit, Environment environment,
			@Value("${trustvault.registration.key}") String signingKey,
			@Value("${trustvault.registration.from}") String fromAddress,
			@Value("${trustvault.registration.enabled:true}") boolean enabled,
			@Value("${trustvault.registration.local-otp:false}") boolean localOtpEnabled) {
		boolean developmentProfileActive = environment.matchesProfiles("dev");
		if (localOtpEnabled && !developmentProfileActive) {
			throw new IllegalStateException("The local registration OTP inbox is only available in the dev profile.");
		}
		if (enabled && signingKey.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("Registration OTP HMAC key must be at least 32 UTF-8 bytes.");
		}
		this.users = users;
		this.challenges = challenges;
		this.passwordEncoder = passwordEncoder;
		this.mailSender = mailSender;
		this.audit = audit;
		this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
		this.fromAddress = fromAddress;
		this.enabled = enabled;
		this.localOtpEnabled = localOtpEnabled;
	}

	@Transactional
	public String requestVerification(String fullName, String email, String username, String password) {
		if (!enabled) {
			throw new RegistrationUnavailableException();
		}
		challenges.deleteByExpiresAtBefore(Instant.now());
		String normalizedUsername = normalize(username);
		String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		if (users.existsById(normalizedUsername) || challenges.existsByUsernameAndEmailNot(normalizedUsername, normalizedEmail)) {
			throw new UsernameAlreadyRegisteredException();
		}
		if (users.existsByEmail(normalizedEmail)) {
			throw new EmailAlreadyRegisteredException();
		}
		if (password == null || password.isBlank()) {
			throw new IllegalArgumentException("Password is required.");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new IllegalArgumentException("Password exceeds the supported length.");
		}


		Instant now = Instant.now();
		RegistrationChallenge existing = challenges.lockByEmail(normalizedEmail).orElse(null);
		if (existing != null && existing.getSentAt().plus(REQUEST_COOLDOWN).isAfter(now)) {
			throw new VerificationCooldownException();
		}

		String code = newCode();
		if (!localOtpEnabled) {
			sendCode(normalizedEmail, code);
		}
		String passwordHash = passwordEncoder.encode(password);
		if (existing == null) {
			challenges.save(new RegistrationChallenge(normalizedEmail, normalizedUsername, fullName.trim(),
					passwordHash, hashCode(normalizedEmail, code), now.plus(CODE_LIFETIME), now, now, TERMS_VERSION));
		} else {
			existing.update(normalizedUsername, fullName.trim(), passwordHash, hashCode(normalizedEmail, code),
					now.plus(CODE_LIFETIME), now, now, TERMS_VERSION);
		}
		audit.recordSuccess(normalizedUsername, "REGISTRATION_OTP_SENT", null,
				localOtpEnabled ? "Account registration code generated in local development inbox"
						: "Account registration verification code sent");
		return localOtpEnabled ? code : null;
	}

	@Transactional
	public String resendVerification(String submittedEmail) {
		if (!enabled) {
			throw new RegistrationUnavailableException();
		}
		challenges.deleteByExpiresAtBefore(Instant.now());
		String email = normalizeEmail(submittedEmail);
		RegistrationChallenge challenge = challenges.lockByEmail(email).orElse(null);
		if (challenge == null) {
			return null;
		}
		Instant now = Instant.now();
		if (challenge.getSentAt().plus(REQUEST_COOLDOWN).isAfter(now)) {
			throw new VerificationCooldownException();
		}
		String code = newCode();
		if (!localOtpEnabled) {
			sendCode(email, code);
		}
		challenge.update(challenge.getUsername(), challenge.getFullName(), challenge.getPasswordHash(),
				hashCode(email, code), now.plus(CODE_LIFETIME), now, challenge.getTermsAcceptedAt(),
				challenge.getTermsVersion());
		audit.recordSuccess(challenge.getUsername(), "REGISTRATION_OTP_SENT", null,
				localOtpEnabled ? "Account registration code regenerated in local development inbox"
						: "Account registration verification code resent");
		return localOtpEnabled ? code : null;
	}

	public boolean isLocalOtpEnabled() {
		return localOtpEnabled;
	}

	@Transactional(noRollbackFor = InvalidVerificationCodeException.class)
	public void verifyAndRegister(String submittedEmail, String code) {
		if (!enabled) {
			throw new RegistrationUnavailableException();
		}
		challenges.deleteByExpiresAtBefore(Instant.now());
		String email = normalizeEmail(submittedEmail);
		RegistrationChallenge challenge = challenges.lockByEmail(email).orElse(null);
		if (challenge == null) {
			throw new InvalidVerificationCodeException();
		}
		if (!challenge.getExpiresAt().isAfter(Instant.now())
				|| challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
			challenges.delete(challenge);
			throw new InvalidVerificationCodeException();
		}
		if (code == null || !code.trim().matches("[0-9]{6}") || !MessageDigest.isEqual(
				hashCode(email, code.trim()).getBytes(StandardCharsets.UTF_8),
				challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
			challenge.recordFailedAttempt();
			if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
				challenges.delete(challenge);
			}
			audit.recordDenied(challenge.getUsername(), "REGISTRATION_EMAIL_OTP", null,
					"Invalid account registration email verification code");
			throw new InvalidVerificationCodeException();
		}
		if (users.existsById(challenge.getUsername())) {
			challenges.delete(challenge);
			throw new UsernameAlreadyRegisteredException();
		}
		if (users.existsByEmail(email)) {
			challenges.delete(challenge);
			throw new EmailAlreadyRegisteredException();
		}

		users.saveAndFlush(new AppUser(challenge.getUsername(), challenge.getPasswordHash(),
				challenge.getFullName(), email, Role.VIEWER, challenge.getTermsAcceptedAt(),
				challenge.getTermsVersion()));
		challenges.delete(challenge);
		audit.recordSuccess(challenge.getUsername(), "ACCOUNT_REGISTERED", null,
				"Email verified and Terms and Conditions accepted (" + challenge.getTermsVersion() + ")");
	}

	private void sendCode(String email, String code) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(email);
		message.setSubject("TrustVault account verification code");
		message.setText("Your TrustVault account verification code is " + code
				+ ". It expires in 10 minutes. If you did not request an account, ignore this email.");
		mailSender.send(message);
	}

	private String newCode() {
		return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
	}

	private String hashCode(String email, String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal((email + "\n" + code).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to protect registration verification code", exception);
		}
	}

	private static String normalize(String username) {
		return username.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	public static class UsernameAlreadyRegisteredException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	public static class EmailAlreadyRegisteredException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	public static class VerificationCooldownException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	public static class InvalidVerificationCodeException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	public static class RegistrationUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
