package com.example.trustvault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.trustvault.model.Role;
import com.example.trustvault.repository.AppUserRepository;
import com.example.trustvault.repository.RegistrationChallengeRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "trustvault.registration.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationTests {
	private static final String USERNAME = "newviewer";
	private static final String SECOND_USERNAME = "newviewer2";
	private static final String EMAIL = "newviewer@example.test";
	private static final String SECOND_EMAIL = "newviewer2@example.test";
	private static final String UNDERSCORE_EMAIL = "trail-account@example.test";
	private static final String UNDERSCORE_USERNAME = "trail_account";
	private static final String PASSWORD = "LongEnough!Password123";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private RegistrationChallengeRepository challenges;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private JavaMailSender mailSender;

	@AfterEach
	void cleanup() {
		users.deleteById(USERNAME);
		users.deleteById(SECOND_USERNAME);
		users.deleteById(UNDERSCORE_USERNAME);
		challenges.deleteById(EMAIL);
		challenges.deleteById(SECOND_EMAIL);
		challenges.deleteById(UNDERSCORE_EMAIL);
	}

	@Test
	void registrationPageRequiresTermsAndOffersEmailVerification() throws Exception {
		mvc.perform(get("/register"))
				.andExpect(status().isOk())
				.andExpect(view().name("register"))
				.andExpect(content().string(containsString("name=\"fullName\"")))
				.andExpect(content().string(containsString("name=\"email\"")))
				.andExpect(content().string(containsString("name=\"username\"")))
				.andExpect(content().string(containsString("name=\"password\"")))
				.andExpect(content().string(containsString("name=\"acceptedTerms\"")))
				.andExpect(content().string(containsString("/terms")));
	}

	@Test
	void loginPageShowsAccountWarningOnlyAfterFailedLogin() throws Exception {
		mvc.perform(get("/login"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString(
						"You don't have an account to login. Please create a new account."))));
		mvc.perform(get("/login?error"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"You don't have an account to login. Please create a new account.")));
	}

	@Test
	void correctUsernameWithWrongPasswordGetsPasswordSpecificWarning() throws Exception {
		mvc.perform(post("/login")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("username", "owner")
				.param("password", "incorrect-password"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?passwordError"));

		mvc.perform(post("/login")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("username", "missing-account")
				.param("password", "incorrect-password"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void accountIsCreatedOnlyAfterCorrectEmailOtpAndTermsAcceptanceIsRecorded() throws Exception {
		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "New Viewer")
				.param("email", EMAIL)
				.param("username", "NewViewer")
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/register?verify=true"));

		assertFalse(users.existsById(USERNAME));
		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		assertEquals(EMAIL, mail.getValue().getTo()[0]);
		Matcher matcher = Pattern.compile("code is ([0-9]{6})").matcher(mail.getValue().getText());
		assertTrue(matcher.find());
		String code = matcher.group(1);

		mvc.perform(post("/register/verification/confirm")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", EMAIL)
				.param("code", code))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?registered"));

		var account = users.findById(USERNAME).orElseThrow();
		assertEquals(Role.VIEWER, account.getRole());
		assertEquals("New Viewer", account.getFullName());
		assertEquals(EMAIL, account.getEmail());
		assertTrue(account.isEmailVerified());
		assertNotEquals(PASSWORD, account.getPasswordHash());
		assertTrue(passwordEncoder.matches(PASSWORD, account.getPasswordHash()));
		assertNotNull(account.getTermsAcceptedAt());
		assertEquals("2026-10-23", account.getTermsVersion());
		assertFalse(challenges.existsById(EMAIL));
		mvc.perform(post("/login")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("username", "NEWVIEWER")
				.param("password", PASSWORD))
				.andExpect(status().is3xxRedirection());
	}

	@Test
	void registrationRejectsMissingTermsAndWeakPasswords() throws Exception {
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("username", USERNAME)
				.param("fullName", "New Viewer")
				.param("email", EMAIL)
				.param("password", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(view().name("register"))
				.andExpect(content().string(containsString("Create your account")));
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("username", USERNAME)
				.param("fullName", "New Viewer")
				.param("email", EMAIL)
				.param("password", "short")
				.param("acceptedTerms", "true"))
				.andExpect(status().isOk())
				.andExpect(view().name("register"))
				.andExpect(content().string(containsString("Create your account")));
		assertFalse(users.existsById(USERNAME));
		assertFalse(challenges.existsById(EMAIL));
	}

	@Test
	void smtpFailureExplainsConfigurationAndDoesNotCreatePendingOrActiveAccount() throws Exception {
		doThrow(new MailSendException("SMTP unavailable"))
				.when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "New Viewer")
				.param("email", EMAIL)
				.param("username", USERNAME)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().isOk())
				.andExpect(view().name("register"))
				.andExpect(content().string(containsString("MAIL_HOST")))
				.andExpect(content().string(containsString("no account was created")));
		assertFalse(users.existsById(USERNAME));
		assertFalse(challenges.existsById(EMAIL));
	}

	@Test
	void invalidEmailOtpDoesNotCreateAnAccountAndIsCountedAgainstTheLimit() throws Exception {
		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "New Viewer")
				.param("email", EMAIL)
				.param("username", USERNAME)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().is3xxRedirection());

		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		Matcher matcher = Pattern.compile("code is ([0-9]{6})").matcher(mail.getValue().getText());
		assertTrue(matcher.find());
		String wrongCode = matcher.group(1).equals("000000") ? "000001" : "000000";
		for (int attempt = 0; attempt < 5; attempt++) {
			mvc.perform(post("/register/verification/confirm")
					.with(SecurityMockMvcRequestPostProcessors.csrf())
					.param("email", EMAIL)
					.param("code", wrongCode))
					.andExpect(status().is3xxRedirection());
		}
		assertFalse(users.existsById(USERNAME));
		assertFalse(challenges.existsById(EMAIL));
	}

	@Test
	void signupRequiresCsrf() throws Exception {
		mvc.perform(post("/register/verification/request")
				.param("username", USERNAME)
				.param("fullName", "New Viewer")
				.param("email", EMAIL)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().isForbidden());
	}

	@Test
	void duplicateUsernameIsRejectedIgnoringCase() throws Exception {
		registerAndVerify(USERNAME, EMAIL, "NewViewer");
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "Second Viewer")
				.param("email", SECOND_EMAIL)
				.param("username", "NewViewer")
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().isOk())
				.andExpect(view().name("register"));
		assertFalse(challenges.existsById(SECOND_EMAIL));
	}

	@Test
	void usernameWithUnderscoresAndSurroundingWhitespaceIsAcceptedAfterTrimming() throws Exception {
		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "Trail Account")
				.param("email", UNDERSCORE_EMAIL)
				.param("username", "  trail_account  ")
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/register?verify=true"));

		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		Matcher matcher = Pattern.compile("code is ([0-9]{6})").matcher(mail.getValue().getText());
		assertTrue(matcher.find());
		mvc.perform(post("/register/verification/confirm")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", UNDERSCORE_EMAIL)
				.param("code", matcher.group(1)))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?registered"));

		assertTrue(users.existsById(UNDERSCORE_USERNAME));
	}

	@Test
	void registrationRequiresValidPersonInformation() throws Exception {
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "")
				.param("email", "not-an-email")
				.param("username", USERNAME)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().isOk())
				.andExpect(view().name("register"));
		assertFalse(users.existsById(USERNAME));
	}

	@Test
	void duplicateEmailIsRejectedIgnoringCase() throws Exception {
		registerAndVerify(USERNAME, EMAIL, USERNAME);
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "Second Viewer")
				.param("email", EMAIL.toUpperCase())
				.param("username", SECOND_USERNAME)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().isOk())
				.andExpect(view().name("register"));
		assertFalse(challenges.existsById(EMAIL));
	}

	private void registerAndVerify(String username, String email, String submittedUsername) throws Exception {
		doNothing().when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post("/register/verification/request")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("fullName", "New Viewer")
				.param("email", email)
				.param("username", submittedUsername)
				.param("password", PASSWORD)
				.param("acceptedTerms", "true"))
				.andExpect(status().is3xxRedirection());
		ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(mail.capture());
		Matcher matcher = Pattern.compile("code is ([0-9]{6})").matcher(mail.getValue().getText());
		assertTrue(matcher.find());
		mvc.perform(post("/register/verification/confirm")
				.with(SecurityMockMvcRequestPostProcessors.csrf())
				.param("email", email)
				.param("code", matcher.group(1)))
				.andExpect(status().is3xxRedirection());
		assertTrue(users.existsById(username));
	}
}
