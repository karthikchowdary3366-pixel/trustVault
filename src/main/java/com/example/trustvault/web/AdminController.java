package com.example.trustvault.web;

import com.example.trustvault.model.Role;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.service.AdminUserService;
import com.example.trustvault.service.AuditService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {
	private final AuditService audit;
	private final AppUserRepository users;
	private final AdminUserService adminUsers;

	public AdminController(AuditService audit, AppUserRepository users, AdminUserService adminUsers) {
		this.audit = audit;
		this.users = users;
		this.adminUsers = adminUsers;
	}

	@GetMapping("/activity")
	public String activity(Model model, Authentication authentication) {
		model.addAttribute("events", audit.latestEvents());
		model.addAttribute("chainValid", audit.verifyChain());
		model.addAttribute("username", authentication.getName());
		model.addAttribute("isAdmin", true);
		return "activity";
	}

	@GetMapping("/users")
	public String users(Model model, Authentication authentication) {
		model.addAttribute("users", users.findAllByOrderByUsernameAsc());
		model.addAttribute("username", authentication.getName());
		return "admin-users";
	}

	@GetMapping("/users/new")
	public String newUser(Model model, Authentication authentication) {
		addUserFormData(model, new AdminUserForm());
		model.addAttribute("username", authentication.getName());
		return "admin-user-form";
	}

	@GetMapping("/users/{username}")
	public String userDetails(@PathVariable String username, Model model, Authentication authentication) {
		model.addAttribute("account", users.findById(username).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found.")));
		model.addAttribute("owners", users.findAllByRoleAndActiveTrueOrderByUsernameAsc(Role.OWNER));
		model.addAttribute("username", authentication.getName());
		return "admin-user-details";
	}

	@PostMapping("/users")
	public String createUser(@Valid @ModelAttribute("newUser") AdminUserForm form, BindingResult result,
			Authentication authentication, Model model, RedirectAttributes redirect) {
		if (form.getPassword() != null && form.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
			result.rejectValue("password", "password.tooLong", "Password must be no more than 72 UTF-8 bytes.");
		}
		if (form.getRole() != Role.VIEWER && form.getRole() != Role.OWNER) {
			result.rejectValue("role", "role.invalid", "Choose Viewer or Owner.");
		}
		if (result.hasErrors()) {
			form.setPassword(null);
			addUserFormData(model, form);
			model.addAttribute("username", authentication.getName());
			return "admin-user-form";
		}
		try {
			adminUsers.createUser(authentication.getName(), form.getUsername(), form.getFullName(), form.getEmail(),
					form.getPassword(), form.getRole());
		} catch (AdminUserService.AccountAlreadyExistsException exception) {
			result.reject("account.exists", exception.getMessage());
			form.setPassword(null);
			addUserFormData(model, form);
			model.addAttribute("username", authentication.getName());
			return "admin-user-form";
		} catch (DataIntegrityViolationException exception) {
			result.reject("account.exists", "That username or email address is already in use.");
			form.setPassword(null);
			addUserFormData(model, form);
			model.addAttribute("username", authentication.getName());
			return "admin-user-form";
		}
		redirect.addFlashAttribute("notice", form.getUsername() + " was created.");
		return "redirect:/admin/users/" + form.getUsername().trim().toLowerCase(java.util.Locale.ROOT);
	}

	@PostMapping("/users/{username}/demote")
	public String demoteOwner(@PathVariable String username, Authentication authentication,
			RedirectAttributes redirect) {
		adminUsers.demoteOwnerToViewer(authentication.getName(), username);
		redirect.addFlashAttribute("notice", username + " is now a viewer.");
		return "redirect:/admin/users/" + username;
	}

	@PostMapping("/users/{username}/deactivate")
	public String deactivateUser(@PathVariable String username, Authentication authentication,
			RedirectAttributes redirect) {
		updateAccountStatus(username, authentication.getName(), false, redirect);
		return "redirect:/admin/users/" + username;
	}

	@PostMapping("/users/{username}/activate")
	public String activateUser(@PathVariable String username, Authentication authentication,
			RedirectAttributes redirect) {
		updateAccountStatus(username, authentication.getName(), true, redirect);
		return "redirect:/admin/users/" + username;
	}

	@PostMapping("/users/{username}/delete")
	public String deleteUser(@PathVariable String username,
			@RequestParam(required = false) String transferToUsername,
			Authentication authentication, RedirectAttributes redirect) {
		try {
			adminUsers.deleteUser(authentication.getName(), username, transferToUsername);
			redirect.addFlashAttribute("notice", username + " was deleted.");
			return "redirect:/admin/users";
		} catch (org.springframework.web.server.ResponseStatusException exception) {
			redirect.addFlashAttribute("error", exception.getReason());
			return "redirect:/admin/users/" + username;
		}
	}

	private void updateAccountStatus(String username, String actor, boolean active, RedirectAttributes redirect) {
		try {
			adminUsers.setAccountActive(actor, username, active);
			redirect.addFlashAttribute("notice", username + (active ? " was reactivated." : " was deactivated."));
		} catch (org.springframework.web.server.ResponseStatusException exception) {
			redirect.addFlashAttribute("error", exception.getReason());
		}
	}

	private void addUserFormData(Model model, AdminUserForm form) {
		model.addAttribute("roles", List.of(Role.VIEWER, Role.OWNER));
		model.addAttribute("newUser", form);
	}
}
