package com.example.trustvault.security;

import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	UserDetailsService userDetailsService(AppUserRepository users) {
		return username -> users.findById(username.trim().toLowerCase(Locale.ROOT))
				.map(account -> User.withUsername(account.getUsername())
						.password(account.getPasswordHash())
						.roles(account.getRole().name())
						.disabled(!account.isActive())
						.build())
				.orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuditService audit, AppUserRepository users) throws Exception {
		return http
				.addFilterBefore(new ActiveAccountFilter(users, audit), AnonymousAuthenticationFilter.class)
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/", "/login", "/register", "/register/**", "/terms", "/privacy",
								"/password-reset", "/password-reset/**", "/error", "/css/**", "/js/**").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
					if (isDocumentRequest(request)) {
						audit.recordDenied("anonymous", documentAction(request), documentId(request),
								"Unauthenticated document access denied");
						response.setStatus(HttpServletResponse.SC_NOT_FOUND);
					} else {
						response.sendRedirect(request.getContextPath() + "/login");
					}
				}).accessDeniedHandler(documentAccessDeniedHandler(audit)))
				.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/documents", true)
						.failureHandler(loginFailureHandler(users)).permitAll())
				.logout(logout -> logout.logoutSuccessUrl("/login?logout"))
				.build();
	}

	private static AuthenticationFailureHandler loginFailureHandler(AppUserRepository users) {
		return (request, response, exception) -> {
			String submittedUsername = request.getParameter("username");
			boolean usernameExists = submittedUsername != null
					&& users.existsById(submittedUsername.trim().toLowerCase(Locale.ROOT));
			String message = usernameExists ? "passwordError" : "error";
			response.sendRedirect(request.getContextPath() + "/login?" + message);
		};
	}

	private static boolean isDocumentRequest(HttpServletRequest request) {
		String[] segments = requestPath(request).split("/");
		return segments.length >= 3 && "documents".equals(segments[1])
				&& !"upload".equals(segments[2]) && !"search".equals(segments[2]);
	}

	private static String requestPath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String context = request.getContextPath();
		return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
	}

	private static AccessDeniedHandler documentAccessDeniedHandler(AuditService audit) {
		return (request, response, exception) -> {
			if (isDocumentRequest(request)) {
				Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
				String actor = authentication == null || !authentication.isAuthenticated()
						|| "anonymousUser".equals(authentication.getName())
						? "anonymous" : authentication.getName();
				audit.recordDenied(actor, documentAction(request), documentId(request),
						"Security filter denied document request");
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			} else if (requestPath(request).startsWith("/admin/users")) {
				Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
				String actor = authentication == null || !authentication.isAuthenticated()
						|| "anonymousUser".equals(authentication.getName())
						? "anonymous" : authentication.getName();
				audit.recordDenied(actor, adminUserAction(request), null,
						"Unauthorized account management attempt");
				response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			} else {
				response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			}
		};
	}

	private static String adminUserAction(HttpServletRequest request) {
		if ("POST".equals(request.getMethod())) {
			String[] segments = requestPath(request).split("/");
			if (segments.length == 3) {
				return "USER_CREATE";
			}
			if (segments.length >= 5 && "delete".equals(segments[4])) {
				return "USER_DELETE";
			}
			if (segments.length >= 5 && "demote".equals(segments[4])) {
				return "ROLE_CHANGE";
			}
			if (segments.length >= 5
					&& ("activate".equals(segments[4]) || "deactivate".equals(segments[4]))) {
				return "USER_STATUS";
			}
		}
		return "USER_MANAGEMENT_ACCESS";
	}

	private static String documentId(HttpServletRequest request) {
		String[] segments = requestPath(request).split("/");
		return segments.length >= 3 ? segments[2] : null;
	}

	private static String documentAction(HttpServletRequest request) {
		String[] segments = requestPath(request).split("/");
		if ("POST".equals(request.getMethod()) && segments.length >= 4) {
			return switch (segments[3]) {
				case "edit" -> "EDIT";
				case "delete" -> "DELETE";
				case "grants" -> segments.length >= 5 && "revoke".equals(segments[4]) ? "REVOKE" : "GRANT";
				default -> "ACCESS_ATTEMPT";
			};
		}
		if ("GET".equals(request.getMethod()) && segments.length >= 4 && "download".equals(segments[3])) {
			return "DOWNLOAD";
		}
		return "VIEW";
	}
}
