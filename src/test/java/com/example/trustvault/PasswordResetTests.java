package com.example.trustvault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.trustvault.model.AppUser;
import com.example.trustvault.model.Role;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.PasswordResetChallengeRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

@SpringBootTest(properties = "trustvault.password-reset.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetTests {
	private static final String USERNAME = "password-reset-test";
	private static final String EMAIL = "password-reset-test@example.test";
	private static final String OLD_PASSWORD = "OldPassword!123";
	private static final String NEW_PASSWORD = "NewPassword!456";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordResetChallengeRepository challenges;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private JavaMailSender mailSender;

	@BeforeEach
	void createUserAndStubMail() {
		users.saveAndFlush(new AppUser(USERNAME, passwordEncoder.encode(OLD_PASSWORD),
				"Password Reset Test", EMAIL, Role.VIEWER));
		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
	}

	@AfterEach
	void cleanup() {
		challenges.deleteById(USERNAME);
		users.deleteById(USERNAME);
	}

	@Test
	void resetPageIsPublic() throws Exception {
		mvc.perform(get("/password-reset"))
				.andExpect(status().isOk());
	}

	@Test
	void emailedOtpCanBeUsedOnlyToSetANewPassword() throws Exception {
		mvc.perform(post("/password-reset/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", EMAIL))
				.andExpect(status().is3xxRedirection());

		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		assertEquals(EMAIL, mail.getValue().getTo()[0]);
		Matcher codeMatcher = Pattern.compile("code is ([0-9]{6})")
				.matcher(mail.getValue().getText());
		assertTrue(codeMatcher.find());
		String code = codeMatcher.group(1);

		mvc.perform(post("/password-reset/confirm")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", EMAIL)
				.param("code", code)
				.param("newPassword", NEW_PASSWORD)
				.param("confirmPassword", NEW_PASSWORD))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
						"/login?reset"));

		assertTrue(passwordEncoder.matches(NEW_PASSWORD, users.findById(USERNAME).orElseThrow().getPasswordHash()));
		assertFalse(passwordEncoder.matches(OLD_PASSWORD, users.findById(USERNAME).orElseThrow().getPasswordHash()));
		assertFalse(challenges.existsById(USERNAME));
	}

	@Test
	void unknownAccountResetRequestDoesNotRevealWhetherTheAccountExists() throws Exception {
		mvc.perform(post("/password-reset/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", "no-such-account@example.test"))
				.andExpect(status().is3xxRedirection());
		org.mockito.Mockito.verifyNoInteractions(mailSender);
	}

	@Test
	void invalidOtpIsRejectedAndCountsTowardAttemptLimit() throws Exception {
		mvc.perform(post("/password-reset/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", EMAIL))
				.andExpect(status().is3xxRedirection());
		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		Matcher codeMatcher = Pattern.compile("code is ([0-9]{6})")
				.matcher(mail.getValue().getText());
		assertTrue(codeMatcher.find());
		String issuedCode = codeMatcher.group(1);
		String wrongCode = "000000".equals(issuedCode) ? "000001" : "000000";

		for (int attempt = 0; attempt < 5; attempt++) {
			mvc.perform(post("/password-reset/confirm")
					.with(SecurityMockMvcRequestPostProcessors.csrf())
					.param("email", EMAIL)
					.param("code", wrongCode)
					.param("newPassword", NEW_PASSWORD)
					.param("confirmPassword", NEW_PASSWORD))
					.andExpect(status().is3xxRedirection());
		}
		assertFalse(challenges.existsById(USERNAME));
		assertTrue(passwordEncoder.matches(OLD_PASSWORD, users.findById(USERNAME).orElseThrow().getPasswordHash()));
	}
}
