package com.example.trustvault.web;

import com.example.trustvault.service.PasswordResetService;
import com.example.trustvault.service.PasswordResetService.PasswordResetUnavailableException;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PasswordResetController {
	private final PasswordResetService passwordReset;

	public PasswordResetController(PasswordResetService passwordReset) {
		this.passwordReset = passwordReset;
	}

	@GetMapping("/password-reset")
	public String resetPage(@RequestParam(required = false) String email, Model model) {
		model.addAttribute("email", email == null ? "" : email);
		return "password-reset";
	}

	@PostMapping("/password-reset/request")
	public String requestCode(@RequestParam String email, RedirectAttributes redirect) {
		try {
			passwordReset.requestCode(email);
		} catch (PasswordResetUnavailableException exception) {
			redirect.addFlashAttribute("resetError",
					"Password reset is unavailable because email delivery is not configured. Configure SMTP and enable password reset, or contact the TrustVault administrator.");
			redirect.addFlashAttribute("resetEmail", email.trim());
			return "redirect:/password-reset";
		} catch (MailException exception) {
			redirect.addFlashAttribute("resetError",
					"We couldn't send the verification code. Please try again later or contact the administrator.");
			redirect.addFlashAttribute("resetEmail", email.trim());
			return "redirect:/password-reset";
		}
		redirect.addFlashAttribute("resetNotice",
				"A verification code has been sent. It expires in 10 minutes.");
		redirect.addFlashAttribute("resetEmail", email.trim());
		return "redirect:/password-reset";
	}

	@PostMapping("/password-reset/confirm")
	public String confirmReset(@RequestParam String email, @RequestParam String code,
			@RequestParam String newPassword, @RequestParam String confirmPassword,
			RedirectAttributes redirect) {
		try {
			return confirmResetEnabled(email, code, newPassword, confirmPassword, redirect);
		} catch (PasswordResetUnavailableException exception) {
			redirect.addFlashAttribute("resetError",
					"Password reset is unavailable because email delivery is not configured. Configure SMTP and enable password reset, or contact the TrustVault administrator.");
			redirect.addFlashAttribute("resetEmail", email.trim());
			return "redirect:/password-reset";
		}
	}

	private String confirmResetEnabled(String email, String code, String newPassword,
			String confirmPassword, RedirectAttributes redirect) {
		if (!newPassword.equals(confirmPassword)) {
			redirect.addFlashAttribute("resetError", "Passwords do not match.");
			redirect.addFlashAttribute("resetEmail", email);
			return "redirect:/password-reset";
		}
		PasswordResetService.ResetResult result = passwordReset.resetPassword(email, code, newPassword);
		switch (result) {
			case SUCCESS -> redirect.addFlashAttribute("resetNotice",
					"Your password has been reset. You can now sign in.");
			case INVALID_PASSWORD -> {
				redirect.addFlashAttribute("resetError", "Password must be 12–72 UTF-8 bytes.");
				redirect.addFlashAttribute("resetEmail", email);
			}
			case INVALID_CODE -> {
				redirect.addFlashAttribute("resetError",
						"That code is invalid, expired, or has reached its attempt limit. Request a new code.");
				redirect.addFlashAttribute("resetEmail", email);
			}
		}
		return result == PasswordResetService.ResetResult.SUCCESS
				? "redirect:/login?reset" : "redirect:/password-reset";
	}
}
