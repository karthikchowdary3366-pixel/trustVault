package com.example.trustvault.config;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.AuditHead;
import com.example.trustvault.model.Role;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.AuditHeadRepository;
import com.example.trustvault.service.AuditService;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

@Configuration
public class DemoDataInitializer {
	@Bean
	@Order(0)
	CommandLineRunner initializeAuditHead(AuditHeadRepository heads) {
		return args -> {
			if (!heads.existsById(1)) {
				heads.saveAndFlush(new AuditHead(1, ""));
			}
		};
	}

	@Bean
	@Order(1)
	CommandLineRunner seedDemoAccounts(AppUserRepository users, PasswordEncoder passwordEncoder,
			@Value("${trustvault.seed.enabled:true}") boolean seedEnabled) {
		return args -> {
			if (!seedEnabled || users.count() > 0) {
				return;
			}
			users.save(new AppUser("admin", passwordEncoder.encode("AdminDemo!234"),
					"TrustVault Administrator", "admin@example.test", Role.ADMIN));
			users.save(new AppUser("owner", passwordEncoder.encode("OwnerDemo!234"),
					"Document Owner", "owner@example.test", Role.OWNER));
			users.save(new AppUser("viewer", passwordEncoder.encode("ViewerDemo!234"),
					"Demo Viewer", "viewer@example.test", Role.VIEWER));
		};
	}

	@Bean
	@Order(2)
	CommandLineRunner bootstrapAdministrator(AppUserRepository users, PasswordEncoder passwordEncoder,
			AuditService audit,
			@Value("${trustvault.bootstrap-admin.username:}") String username,
			@Value("${trustvault.bootstrap-admin.password:}") String password,
			@Value("${trustvault.bootstrap-admin.full-name:}") String fullName,
			@Value("${trustvault.bootstrap-admin.email:}") String email) {
		return args -> {
			boolean configured = StringUtils.hasText(username) || StringUtils.hasText(password)
					|| StringUtils.hasText(fullName) || StringUtils.hasText(email);
			if (!configured) {
				return;
			}
			if (!StringUtils.hasText(username) || !StringUtils.hasText(password)
					|| !StringUtils.hasText(fullName) || !StringUtils.hasText(email)) {
				throw new IllegalStateException("Bootstrap administrator requires username, password, full name, and email.");
			}
			if (password.getBytes(StandardCharsets.UTF_8).length < 12
					|| password.getBytes(StandardCharsets.UTF_8).length > 72) {
				throw new IllegalStateException("Bootstrap administrator password must be 12–72 UTF-8 bytes.");
			}
			if (users.count() != 0) {
				return;
			}
			String normalizedUsername = username.trim().toLowerCase(java.util.Locale.ROOT);
			String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
			AppUser administrator = users.saveAndFlush(new AppUser(normalizedUsername,
					passwordEncoder.encode(password), fullName.trim(), normalizedEmail, Role.ADMIN));
			audit.recordSuccess(administrator.getUsername(), "BOOTSTRAP_ADMIN", null,
					"Initial administrator account provisioned from operator configuration");
		};
	}
}
