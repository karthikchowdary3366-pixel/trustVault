package com.example.trustvault.web;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.service.AccountEmailVerificationService;
import com.example.trustvault.service.AccountProfileService;
import com.example.trustvault.service.PasswordChangeService;
import com.example.trustvault.service.PasswordChangeService.ChangeResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AccountSettingsController {
	private final AppUserRepository users;
	private final AccountProfileService profiles;
	private final AccountEmailVerificationService emailVerification;
	private final PasswordChangeService passwords;

	public AccountSettingsController(AppUserRepository users, AccountProfileService profiles,
			AccountEmailVerificationService emailVerification, PasswordChangeService passwords) {
		this.users = users;
		this.profiles = profiles;
		this.emailVerification = emailVerification;
		this.passwords = passwords;
	}

	@GetMapping("/account/settings")
	public String settings(Authentication authentication, Model model) {
		AppUser account = users.findById(authentication.getName()).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is unavailable."));
		model.addAttribute("account", account);
		model.addAttribute("username", authentication.getName());
		return "account-settings";
	}

	@PostMapping("/account/settings/username")
	public String changeUsername(Authentication authentication, HttpServletRequest request,
			HttpServletResponse response, @RequestParam String newUsername, @RequestParam String currentPassword,
			RedirectAttributes redirect) {
		try {
			if (newUsername.getBytes(StandardCharsets.UTF_8).length > 80) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be no more than 80 characters.");
			}
			profiles.changeUsername(authentication.getName(), newUsername, currentPassword);
			new SecurityContextLogoutHandler().logout(request, response, authentication);
			return "redirect:/login?usernameChanged";
		} catch (ResponseStatusException exception) {
			redirect.addFlashAttribute("error", exception.getReason());
			return "redirect:/account/settings";
		}
	}

	@PostMapping("/account/settings/full-name")
	public String addFullName(Authentication authentication, @RequestParam String fullName,
			RedirectAttributes redirect) {
		try {
			profiles.addFullName(authentication.getName(), fullName);
			redirect.addFlashAttribute("notice", "Your full name was added.");
		} catch (ResponseStatusException exception) {
			redirect.addFlashAttribute("error", exception.getReason());
		}
		return "redirect:/account/settings";
	}

	@PostMapping("/account/settings/email-verification")
	public String sendVerificationCode(Authentication authentication,
			@RequestParam(required = false) String email, RedirectAttributes redirect) {
		try {
			emailVerification.sendCode(authentication.getName(), email);
			redirect.addFlashAttribute("notice", "A verification code was sent to your email.");
		} catch (ResponseStatusException exception) {
			redirect.addFlashAttribute("error", exception.getReason());
		}
		return "redirect:/account/settings";
	}

	@PostMapping("/account/settings/email-verification/confirm")
	public String confirmEmail(Authentication authentication, @RequestParam String code,
			RedirectAttributes redirect) {
		try {
			emailVerification.verify(authentication.getName(), code);
			redirect.addFlashAttribute("notice", "Your email address is verified.");
		} catch (AccountEmailVerificationService.InvalidVerificationCodeException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		} catch (ResponseStatusException exception) {
			redirect.addFlashAttribute("error", exception.getReason());
		}
		return "redirect:/account/settings";
	}

	@PostMapping("/account/settings/password")
	public String changePassword(Authentication authentication,
			@RequestParam String currentPassword,
			@RequestParam String newPassword,
			@RequestParam String confirmPassword,
			RedirectAttributes redirect) {
		if (!newPassword.equals(confirmPassword)) {
			redirect.addFlashAttribute("error", "New passwords do not match.");
			return "redirect:/account/settings";
		}
		ChangeResult result = passwords.changePassword(authentication.getName(), currentPassword, newPassword);
		switch (result) {
			case SUCCESS -> redirect.addFlashAttribute("notice", "Your password has been changed.");
			case INVALID_CURRENT_PASSWORD -> redirect.addFlashAttribute("error", "Current password is incorrect.");
			case INVALID_PASSWORD -> redirect.addFlashAttribute("error", "Password must be 12–72 UTF-8 bytes.");
			case SAME_PASSWORD -> redirect.addFlashAttribute("error", "Choose a password different from your current one.");
		}
		return "redirect:/account/settings";
	}
}
