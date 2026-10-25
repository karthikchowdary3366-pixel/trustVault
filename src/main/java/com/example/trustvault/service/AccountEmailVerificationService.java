package com.example.trustvault.service;

import com.example.trustvault.model.AccountEmailVerificationChallenge;
import com.example.trustvault.model.AppUser;
import com.example.trustvault.repository.AccountEmailVerificationChallengeRepository;
import com.example.trustvault.repository.AppUserRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountEmailVerificationService {
	private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
	private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(1);
	private static final int MAX_ATTEMPTS = 5;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AppUserRepository users;
	private final AccountEmailVerificationChallengeRepository challenges;
	private final JavaMailSender mailSender;
	private final AuditService audit;
	private final byte[] signingKey;
	private final String fromAddress;

	public AccountEmailVerificationService(AppUserRepository users,
			AccountEmailVerificationChallengeRepository challenges, JavaMailSender mailSender, AuditService audit,
			@Value("${trustvault.registration.key}") String signingKey,
			@Value("${trustvault.registration.from}") String fromAddress) {
		this.users = users;
		this.challenges = challenges;
		this.mailSender = mailSender;
		this.audit = audit;
		this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
		this.fromAddress = fromAddress;
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional
	public void sendCode(String username, String submittedEmail) {
		AppUser user = users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		if (user.isEmailVerified()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This email address is already verified.");
		}
		String email = user.getEmail();
		if (email == null || email.isBlank()) {
			email = submittedEmail == null ? "" : submittedEmail.strip().toLowerCase(Locale.ROOT);
			if (!isValidEmail(email)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address.");
			}
			if (users.existsByEmail(email)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "That email address is already in use.");
			}
		} else if (submittedEmail != null && !email.equalsIgnoreCase(submittedEmail.strip())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"This account already has an email address. Verify the address shown in your profile.");
		}
		Instant now = Instant.now();
		AccountEmailVerificationChallenge challenge = challenges.lockByUsername(username).orElse(null);
		if (challenge != null && challenge.getSentAt().plus(REQUEST_COOLDOWN).isAfter(now)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Wait one minute before requesting another verification code.");
		}
		String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(email);
		message.setSubject("TrustVault email verification code");
		message.setText("Your TrustVault email verification code is " + code
				+ ". It expires in 10 minutes. If you did not request this, ignore this email.");
		try {
			mailSender.send(message);
		} catch (MailException exception) {
			audit.recordDenied(username, "ACCOUNT_EMAIL_OTP_SENT", null,
					"Account email verification code delivery failed");
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Unable to send a verification email. Check the email service configuration.", exception);
		}
		String codeHash = hashCode(username, email, code);
		if (user.getEmail() == null || user.getEmail().isBlank()) {
			user.changeEmail(email);
		}
		if (challenge == null) {
			challenges.save(new AccountEmailVerificationChallenge(username, codeHash, email,
					now.plus(CODE_LIFETIME), now));
		} else {
			challenge.reset(codeHash, email, now.plus(CODE_LIFETIME), now);
		}
		audit.recordSuccess(username, "ACCOUNT_EMAIL_OTP_SENT", null,
				"Account email verification code sent");
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional(noRollbackFor = InvalidVerificationCodeException.class)
	public void verify(String username, String submittedCode) {
		AppUser user = users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		if (user.isEmailVerified()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This email address is already verified.");
		}
		AccountEmailVerificationChallenge challenge = challenges.lockByUsername(username).orElse(null);
		if (challenge == null || challenge.getEmail() == null || !challenge.getExpiresAt().isAfter(Instant.now())
				|| challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
			if (challenge != null) {
				challenges.delete(challenge);
			}
			throw new InvalidVerificationCodeException("The code is invalid or expired. Request a new code.");
		}
		if (!challenge.getEmail().equalsIgnoreCase(user.getEmail()) || submittedCode == null
				|| !submittedCode.trim().matches("[0-9]{6}")
				|| !MessageDigest.isEqual(hashCode(username, challenge.getEmail(), submittedCode.trim())
						.getBytes(StandardCharsets.UTF_8),
						challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
			challenge.recordFailedAttempt();
			if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
				challenges.delete(challenge);
			}
			audit.recordDenied(username, "ACCOUNT_EMAIL_OTP", null,
					"Invalid account email verification code");
			throw new InvalidVerificationCodeException("The code is invalid. Check it and try again.");
		}
		user.setEmailVerified(true);
		challenges.delete(challenge);
		audit.recordSuccess(username, "ACCOUNT_EMAIL_VERIFIED", null, "Account email address verified");
	}

	private static boolean isValidEmail(String email) {
		return email.length() <= 254
				&& email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
	}

	private String hashCode(String username, String email, String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
			return HexFormat.of().formatHex(
					mac.doFinal((username + "\n" + email + "\n" + code).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to protect account email verification code", exception);
		}
	}

	public static class InvalidVerificationCodeException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public InvalidVerificationCodeException(String message) {
			super(message);
		}
	}
}
