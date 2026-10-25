package com.example.trustvault;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.RegistrationChallengeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
		"trustvault.registration.enabled=true",
		"trustvault.registration.local-otp=true"
})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class RegistrationLocalOtpTests {
	private static final String USERNAME = "local-otp-test";
	private static final String EMAIL = "local-otp-test@example.test";
	private static final String PASSWORD = "LocalOtpTest!Password";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private RegistrationChallengeRepository challenges;

	@MockitoBean
	private JavaMailSender mailSender;

	@AfterEach
	void cleanup() {
		users.deleteById(USERNAME);
		challenges.deleteById(EMAIL);
	}

	@Test
	void localInboxAllowsLoopbackRegistrationWithoutSmtp() throws Exception {
		MvcResult requestResult = mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "Local OTP")
				.param("email", EMAIL)
				.param("username", USERNAME)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/register?verify=true"))
				.andReturn();

		String code = (String) requestResult.getFlashMap().get("developmentCode");
		assertNotNull(code);
		assertFalse(users.existsById(USERNAME));
		verifyNoInteractions(mailSender);

		mvc.perform(get("/register?verify=true").flashAttrs(requestResult.getFlashMap()))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString(code)))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"does not verify mailbox ownership")));

		mvc.perform(post("/register/verification/confirm")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", EMAIL)
				.param("code", code))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?registered"));
		assertNotNull(users.findById(USERNAME).orElseThrow());
	}

	@Test
	void localInboxRejectsNonLoopbackRequests() throws Exception {
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.with(request -> {
					request.setRemoteAddr("192.0.2.10");
					return request;
				})
				.param("fullName", "Remote OTP")
				.param("email", EMAIL)
				.param("username", USERNAME)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"available only from this machine")));
		assertFalse(users.existsById(USERNAME));
		assertFalse(challenges.existsById(EMAIL));
		verifyNoInteractions(mailSender);
	}
}
