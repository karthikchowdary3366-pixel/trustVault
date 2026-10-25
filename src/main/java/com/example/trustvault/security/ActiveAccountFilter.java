package com.example.trustvault.security;

import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class ActiveAccountFilter extends OncePerRequestFilter {
	private final AppUserRepository users;
	private final AuditService audit;

	ActiveAccountFilter(AppUserRepository users, AuditService audit) {
		this.users = users;
		this.audit = audit;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)
				&& users.findById(authentication.getName()).map(user -> !user.isActive()).orElse(true)) {
			audit.recordDenied(authentication.getName(), "ACCOUNT_ACCESS", null,
					"Inactive or unavailable account attempted to access the application");
			SecurityContextHolder.clearContext();
			var session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			response.sendRedirect(request.getContextPath() + "/login?disabled");
			return;
		}
		chain.doFilter(request, response);
	}
}
