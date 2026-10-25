package com.example.trustvault.web;

import com.example.trustvault.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public class AdminUserForm {
	@NotBlank
	@Size(max = 120, message = "Full name must be 120 characters or fewer.")
	private String fullName;

	@NotBlank
	@Email(message = "Enter a valid email address.")
	@Size(max = 254, message = "Email address must be 254 characters or fewer.")
	private String email;

	@NotBlank
	@Size(min = 3, max = 80)
	@Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*",
			message = "Use 3–80 letters, numbers, dots, underscores, or hyphens; start with a letter or number.")
	private String username;

	@NotBlank
	@Size(min = 12, max = 72, message = "Use at least 12 characters and no more than 72.")
	private String password;

	@NotNull
	private Role role = Role.VIEWER;

	public String getFullName() {
		return fullName;
	}

	public void setFullName(String fullName) {
		this.fullName = fullName == null ? null : fullName.strip();
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username == null ? null : username.strip().toLowerCase(Locale.ROOT);
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}
}
