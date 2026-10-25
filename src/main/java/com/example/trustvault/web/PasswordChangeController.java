package com.example.trustvault.web;

import com.example.trustvault.service.PasswordChangeService;
import com.example.trustvault.service.PasswordChangeService.ChangeResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PasswordChangeController {
	private final PasswordChangeService passwords;

	public PasswordChangeController(PasswordChangeService passwords) {
		this.passwords = passwords;
	}

	@GetMapping("/account/password")
	public String changePasswordPage() {
		return "redirect:/account/settings";
	}

	@PostMapping("/account/password")
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
