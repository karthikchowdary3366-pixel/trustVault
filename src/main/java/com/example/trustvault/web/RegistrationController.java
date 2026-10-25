package com.example.trustvault.web;

import com.example.trustvault.service.RegistrationService;
import com.example.trustvault.service.RegistrationService.EmailAlreadyRegisteredException;
import com.example.trustvault.service.RegistrationService.InvalidVerificationCodeException;
import com.example.trustvault.service.RegistrationService.RegistrationUnavailableException;
import com.example.trustvault.service.RegistrationService.UsernameAlreadyRegisteredException;
import com.example.trustvault.service.RegistrationService.VerificationCooldownException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RegistrationController {
	private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationController.class);
	private static final String MAIL_CONFIGURATION_ERROR =
			"TrustVault couldn't connect to the configured email service, so no verification code was sent and no account was created. Check the SMTP settings (MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_SMTP_AUTH, and MAIL_SMTP_STARTTLS), then try again.";

	private final RegistrationService registration;

	public RegistrationController(RegistrationService registration) {
		this.registration = registration;
	}

	@GetMapping("/register")
	public String registerForm(@RequestParam(required = false) boolean verify, Model model) {
		model.addAttribute("registrationForm", new RegistrationForm());
		model.addAttribute("verificationStep", verify);
		model.addAttribute("localOtpMode", registration.isLocalOtpEnabled());
		return "register";
	}

	@PostMapping("/register/verification/request")
	public String requestVerification(@Valid @ModelAttribute("registrationForm") RegistrationForm form,
			BindingResult result, RedirectAttributes redirect, HttpServletRequest request, Model model) {
		model.addAttribute("localOtpMode", registration.isLocalOtpEnabled());
		model.addAttribute("verificationStep", false);
		if (registration.isLocalOtpEnabled() && !isLoopbackRequest(request)) {
			result.reject("verification.localOnly", "The local OTP inbox is available only from this machine.");
			return "register";
		}
		if (form.getPassword() != null
				&& form.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
			result.rejectValue("password", "password.tooLong",
					"Password must be no more than 72 UTF-8 bytes.");
		}
		if (result.hasErrors()) {
			return "register";
		}
		try {
			String developmentCode = registration.requestVerification(form.getFullName(), form.getEmail(), form.getUsername(),
					form.getPassword());
			if (developmentCode != null) {
				redirect.addFlashAttribute("developmentCode", developmentCode);
				redirect.addFlashAttribute("registrationNotice",
						"Local development mode: this code is shown here instead of being emailed.");
			}
		} catch (EmailAlreadyRegisteredException exception) {
			result.rejectValue("email", "email.taken", "That email address is already registered.");
			return "register";
		} catch (UsernameAlreadyRegisteredException | DataIntegrityViolationException exception) {
			result.rejectValue("username", "username.taken", "That username is already in use.");
			return "register";
		} catch (VerificationCooldownException exception) {
			result.reject("verification.cooldown",
					"A verification code was sent recently. Check your email or wait one minute before requesting another.");
			return "register";
		} catch (RegistrationUnavailableException exception) {
			result.reject("verification.unavailable",
					"Email verification is not enabled. Please contact the TrustVault administrator.");
			return "register";
		} catch (MailException exception) {
			LOGGER.warn("Registration verification email delivery failed; SMTP exception type: {}",
					rootCause(exception).getClass().getSimpleName());
			result.reject("verification.delivery", MAIL_CONFIGURATION_ERROR);
			return "register";
		}
		redirect.addFlashAttribute("registrationEmail", form.getEmail().trim().toLowerCase(Locale.ROOT));
		return "redirect:/register?verify=true";
	}

	@PostMapping("/register/verification/resend")
	public String resendVerification(@RequestParam String email, RedirectAttributes redirect,
			HttpServletRequest request) {
		if (registration.isLocalOtpEnabled() && !isLoopbackRequest(request)) {
			redirect.addFlashAttribute("registrationError",
					"The local OTP inbox is available only from this machine.");
			redirect.addFlashAttribute("registrationEmail", email.trim());
			return "redirect:/register?verify=true";
		}
		try {
			String developmentCode = registration.resendVerification(email);
			if (developmentCode != null) {
				redirect.addFlashAttribute("developmentCode", developmentCode);
				redirect.addFlashAttribute("registrationNotice",
						"Local development mode: the new code is shown here instead of being emailed.");
			} else {
				redirect.addFlashAttribute("registrationNotice",
						"If registration is pending for that address, a new code has been sent.");
			}
		} catch (VerificationCooldownException exception) {
			redirect.addFlashAttribute("registrationError",
					"Please wait one minute between verification code requests.");
		} catch (RegistrationUnavailableException exception) {
			redirect.addFlashAttribute("registrationError",
					"Email verification is not enabled. Please contact the TrustVault administrator.");
		} catch (MailException exception) {
			LOGGER.warn("Registration verification email resend failed; SMTP exception type: {}",
					rootCause(exception).getClass().getSimpleName());
			redirect.addFlashAttribute("registrationError",
					MAIL_CONFIGURATION_ERROR);
		}
		redirect.addFlashAttribute("registrationEmail", email.trim());
		return "redirect:/register?verify=true";
	}

	private static boolean isLoopbackRequest(HttpServletRequest request) {
		try {
			return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
		} catch (UnknownHostException exception) {
			return false;
		}
	}

	private static Throwable rootCause(Throwable exception) {
		Throwable root = exception;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root;
	}

	@PostMapping("/register/verification/confirm")
	public String confirmVerification(@RequestParam String email, @RequestParam String code,
			RedirectAttributes redirect) {
		try {
			registration.verifyAndRegister(email, code);
		} catch (InvalidVerificationCodeException exception) {
			redirect.addFlashAttribute("registrationError",
					"That verification code is invalid, expired, or has reached its attempt limit. Request a new code.");
			redirect.addFlashAttribute("registrationEmail", email.trim());
			return "redirect:/register?verify=true";
		} catch (UsernameAlreadyRegisteredException exception) {
			redirect.addFlashAttribute("registrationError",
					"That username is already in use. Start registration again with a different username.");
			return "redirect:/register";
		} catch (EmailAlreadyRegisteredException | DataIntegrityViolationException exception) {
			redirect.addFlashAttribute("registrationError",
					"That email address is already registered. Please sign in or use another email.");
			return "redirect:/register";
		} catch (DataAccessException | TransactionException | IllegalStateException exception) {
			LOGGER.error("Registration OTP confirmation could not complete", exception);
			redirect.addFlashAttribute("registrationError",
					"TrustVault could not complete email verification or save the account. No account was created. Please try again later or contact the administrator.");
			redirect.addFlashAttribute("registrationEmail", email.trim());
			return "redirect:/register?verify=true";
		} catch (RegistrationUnavailableException exception) {
			redirect.addFlashAttribute("registrationError",
					"Email verification is not enabled. Please contact the TrustVault administrator.");
			redirect.addFlashAttribute("registrationEmail", email.trim());
			return "redirect:/register?verify=true";
		}
		return "redirect:/login?registered";
	}
}
