package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** /forgot-password and /reset-password: forms, validation, the no-account-leak answer and the full reset journey. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetPageTest {

	static final String EMAIL = PasswordResetServiceTest.EMAIL;

	static final String OLD_PASSWORD = PasswordResetServiceTest.OLD_PASSWORD;

	static final String NEW_PASSWORD = PasswordResetServiceTest.NEW_PASSWORD;

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordResetService resets;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void createAccount() {
		users.save(new User("Asha Patil", EMAIL, "9876543210", passwordEncoder.encode(OLD_PASSWORD), Role.CUSTOMER));
	}

	@AfterEach
	void cleanUp() {
		PasswordResetServiceTest.deleteTestAccounts(users);
	}

	@Test
	void forgotPasswordPageAsksForTheEmail() throws Exception {
		String html = mvc.perform(get("/forgot-password"))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/forgot-password"))
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("<title>Reset your password | Zaika</title>", "Forgot your password?",
				"action=\"/forgot-password\"", "name=\"_csrf\"", "id=\"email\"", "autocomplete=\"username\"",
				"Send reset link", "href=\"/login\"", "Keep your account safe")
			.doesNotContain("Check your inbox", "<script>");
	}

	@Test
	void knownAndUnknownEmailsGetExactlyTheSameAnswer(CapturedOutput output) throws Exception {
		MvcResult known = mvc.perform(post("/forgot-password").param("email", EMAIL).with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/forgot-password"))
			.andExpect(flash().attribute("sentTo", EMAIL))
			.andReturn();
		MvcResult unknown = mvc.perform(post("/forgot-password").param("email", "nobody@reset-test.zaika.test").with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/forgot-password"))
			.andExpect(flash().attribute("sentTo", "nobody@reset-test.zaika.test"))
			.andReturn();

		assertThat(known.getFlashMap().keySet()).isEqualTo(unknown.getFlashMap().keySet());
		assertThat(output).contains("to=" + EMAIL).doesNotContain("to=nobody@reset-test.zaika.test");

		String knownPage = withoutCsrfTokens(render(known));
		String unknownPage = withoutCsrfTokens(render(unknown));
		assertThat(knownPage).contains("Check your inbox", "<strong>" + EMAIL + "</strong>", "expires in 30 minutes",
				"Back to sign in");
		assertThat(unknownPage.replace("nobody@reset-test.zaika.test", EMAIL)).isEqualTo(knownPage);
	}

	@Test
	void forgotPasswordValidatesTheEmailInline() throws Exception {
		String blank = mvc.perform(post("/forgot-password").param("email", " ").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/forgot-password"))
			.andExpect(model().attributeHasFieldErrors("form", "email"))
			.andReturn().getResponse().getContentAsString();
		assertThat(blank).contains("Enter the email you signed up with.", "aria-invalid=\"true\"");

		String invalid = mvc.perform(post("/forgot-password").param("email", "asha-at-example").with(csrf()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(invalid).contains("Enter a valid email address", "value=\"asha-at-example\"");
	}

	@Test
	void formsNeedTheCsrfToken() throws Exception {
		mvc.perform(post("/forgot-password").param("email", EMAIL)).andExpect(status().isForbidden());
		mvc.perform(post("/reset-password").param("token", "x").param("password", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD)).andExpect(status().isForbidden());
	}

	@Test
	void validLinkShowsTheNewPasswordForm(CapturedOutput output) throws Exception {
		String token = requestLink(output);

		String html = mvc.perform(get("/reset-password").param("token", token))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/reset-password"))
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("<title>Choose a new password | Zaika</title>", "action=\"/reset-password\"",
				"type=\"hidden\"", "name=\"token\"", "value=\"" + token + "\"", "id=\"password\"", "id=\"confirmPassword\"",
				"autocomplete=\"new-password\"", "Update password")
			.doesNotContain("This link has expired");
	}

	@Test
	void unknownOrMissingLinkShowsTheExpiredState() throws Exception {
		for (var request : new RequestBuilder[] {
				get("/reset-password").param("token", "made-up"), get("/reset-password") }) {
			String html = mvc.perform(request)
				.andExpect(status().isOk())
				.andExpect(view().name("auth/reset-password"))
				.andExpect(model().attribute("linkExpired", true))
				.andReturn().getResponse().getContentAsString();
			assertThat(html).contains("This link has expired", "href=\"/forgot-password\">Send a new link")
				.doesNotContain("action=\"/reset-password\"");
		}
	}

	@Test
	void weakOrMismatchedPasswordsShowInlineErrorsAndKeepTheLinkAlive(CapturedOutput output) throws Exception {
		String token = requestLink(output);

		String weak = mvc.perform(post("/reset-password").param("token", token).param("password", "short")
			.param("confirmPassword", "short").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/reset-password"))
			.andExpect(model().attributeHasFieldErrors("form", "password"))
			.andReturn().getResponse().getContentAsString();
		assertThat(weak).contains(RegistrationForm.PASSWORD_RULE, "value=\"" + token + "\"").doesNotContain("value=\"short\"");

		mvc.perform(post("/reset-password").param("token", token).param("password", NEW_PASSWORD)
			.param("confirmPassword", "Different9").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "confirmPassword", "mismatch"));

		assertThat(resets.isUsable(token)).isTrue();
		assertThat(passwordEncoder.matches(OLD_PASSWORD, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
	}

	@Test
	void fullJourneyFromForgotPasswordToSigningInWithTheNewPassword(CapturedOutput output) throws Exception {
		mvc.perform(post("/forgot-password").param("email", EMAIL).with(csrf())).andExpect(status().isFound());
		String token = PasswordResetServiceTest.lastToken(output);

		MvcResult reset = mvc.perform(post("/reset-password").param("token", token).param("password", NEW_PASSWORD)
			.param("confirmPassword", NEW_PASSWORD).with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/login"))
			.andExpect(flash().attribute("email", EMAIL))
			.andExpect(flash().attribute("flashSuccess", "Password updated. Sign in with your new password."))
			.andReturn();

		String loginPage = render("/login", reset);
		assertThat(loginPage).contains("Password updated. Sign in with your new password.", "value=\"" + EMAIL + "\"");

		mvc.perform(login(OLD_PASSWORD)).andExpect(unauthenticated());
		mvc.perform(login(NEW_PASSWORD)).andExpect(authenticated());

		mvc.perform(post("/reset-password").param("token", token).param("password", "Another9pass")
			.param("confirmPassword", "Another9pass").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(model().attribute("linkExpired", true));
		assertThat(passwordEncoder.matches(NEW_PASSWORD, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
	}

	/** CSRF tokens differ per session; everything else must match byte for byte. */
	private static String withoutCsrfTokens(String html) {
		return html.replaceAll("(content|value)=\"[A-Za-z0-9_=-]{30,}\"", "$1=\"csrf\"");
	}

	private String requestLink(CapturedOutput output) {
		resets.requestReset(EMAIL);
		return PasswordResetServiceTest.lastToken(output);
	}

	private String render(MvcResult redirect) throws Exception {
		return render("/forgot-password", redirect);
	}

	private String render(String url, MvcResult redirect) throws Exception {
		return mvc.perform(get(url).flashAttrs(redirect.getFlashMap()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	private static MockHttpServletRequestBuilder login(String password) {
		return post("/login").param("email", EMAIL).param("password", password).with(csrf());
	}

}
