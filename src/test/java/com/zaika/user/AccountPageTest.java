package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.security.ZaikaUserDetailsService;

/** /account: profile details and password change, including validation and who may get in. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountPageTest {

	static final String EMAIL = "asha@account-test.zaika.test";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@BeforeEach
	void createCustomer() {
		users.save(new User("Asha Patil", EMAIL, "9876543210", passwordEncoder.encode("Masala2026"), Role.CUSTOMER));
	}

	@AfterEach
	void deleteAccounts() {
		users.findAll().stream().filter(u -> u.getEmail().endsWith("@account-test.zaika.test")).forEach(users::delete);
	}

	RequestPostProcessor asha() {
		return user(userDetails.loadUserByUsername(EMAIL));
	}

	User reload() {
		return users.findByEmailIgnoreCase(EMAIL).orElseThrow();
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	@Test
	void accountPageShowsTheProfileAndThePasswordForm() throws Exception {
		String html = squash(mvc.perform(get("/account").with(asha()))
			.andExpect(status().isOk())
			.andExpect(view().name("account/profile"))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("<title>Your account | Zaika</title>", "<h1>Your account</h1>", EMAIL,
					"<a class=\"tab\" href=\"/account\" aria-current=\"page\">", "href=\"/account/addresses\"",
					"action=\"/account/profile\"", "action=\"/account/password#password\"",
					"name=\"name\" value=\"Asha Patil\"", "name=\"phone\" value=\"9876543210\"",
					"id=\"account-email\" value=\"" + EMAIL + "\" readonly",
					"id=\"currentPassword\" name=\"currentPassword\" autocomplete=\"current-password\"",
					"id=\"newPassword\" name=\"newPassword\" autocomplete=\"new-password\"",
					"Save profile", "Change password")
			.doesNotContain("field-error", "role=\"alert\"")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)");
	}

	@Test
	void otherRolesAndVisitorsCannotOpenTheAccountPages() throws Exception {
		mvc.perform(get("/account")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/account/profile").with(csrf()).param("name", "X").param("phone", "9876543210"))
			.andExpect(redirectedUrl("/login"));
		User owner = users.save(new User("Owner", "owner@account-test.zaika.test", "9876500000", "{noop}x",
				Role.RESTAURANT_OWNER));
		mvc.perform(get("/account").with(user(userDetails.loadUserByUsername(owner.getEmail()))))
			.andExpect(status().isForbidden());
		mvc.perform(post("/account/profile").with(csrf()).with(user(userDetails.loadUserByUsername(owner.getEmail())))
			.param("name", "X").param("phone", "9876543210")).andExpect(status().isForbidden());
		assertThat(reload().getName()).isEqualTo("Asha Patil");
	}

	@Test
	void profileChangesAreSaved() throws Exception {
		mvc.perform(post("/account/profile").with(csrf()).with(asha())
				.param("name", "  Asha R. Patil ").param("phone", "+91 70000 12345"))
			.andExpect(redirectedUrl("/account"))
			.andExpect(flash().attribute("flashSuccess", "Profile saved."));
		User saved = reload();
		assertThat(saved.getName()).isEqualTo("Asha R. Patil");
		assertThat(saved.getPhone()).isEqualTo("7000012345");
		assertThat(saved.getEmail()).isEqualTo(EMAIL);
	}

	@Test
	void invalidProfileShowsInlineErrorsAndKeepsTheInput() throws Exception {
		String html = squash(mvc.perform(post("/account/profile").with(csrf()).with(asha())
				.param("name", " ").param("phone", "12345"))
			.andExpect(status().isOk())
			.andExpect(view().name("account/profile"))
			.andExpect(model().attributeHasFieldErrors("profile", "name", "phone"))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("Your profile was not saved.", "Tell us your name.",
					"Enter a 10-digit mobile number starting with 6, 7, 8 or 9.", "name=\"phone\" value=\"12345\"",
					"aria-invalid=\"true\"")
			.doesNotContain("Your password was not changed.");
		assertThat(reload().getPhone()).isEqualTo("9876543210");
	}

	@Test
	void profileFormNeedsACsrfToken() throws Exception {
		mvc.perform(post("/account/profile").with(asha()).param("name", "Hacker").param("phone", "9876543210"))
			.andExpect(status().isForbidden());
		assertThat(reload().getName()).isEqualTo("Asha Patil");
	}

	@Test
	void passwordChangeNeedsTheCurrentPasswordAndThenWorksForSignIn() throws Exception {
		mvc.perform(post("/account/password").with(csrf()).with(asha())
				.param("currentPassword", "Masala2026").param("newPassword", "Kolhapuri99")
				.param("confirmPassword", "Kolhapuri99"))
			.andExpect(redirectedUrl("/account"))
			.andExpect(flash().attribute("flashSuccess", "Password changed. Use the new one next time you sign in."));
		assertThat(passwordEncoder.matches("Kolhapuri99", reload().getPasswordHash())).isTrue();

		mvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", "Masala2026"))
			.andExpect(redirectedUrl("/login?error"));
		mvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", "Kolhapuri99"))
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void wrongCurrentPasswordIsAnInlineErrorAndChangesNothing() throws Exception {
		String before = reload().getPasswordHash();
		String html = squash(mvc.perform(post("/account/password").with(csrf()).with(asha())
				.param("currentPassword", "Guess1234").param("newPassword", "Kolhapuri99")
				.param("confirmPassword", "Kolhapuri99"))
			.andExpect(status().isOk())
			.andExpect(view().name("account/profile"))
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "currentPassword", "wrong"))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("Your password was not changed.", "That isn&#39;t your current password.",
					"name=\"name\" value=\"Asha Patil\"")
			.doesNotContain("value=\"Guess1234\"", "value=\"Kolhapuri99\"");
		assertThat(reload().getPasswordHash()).isEqualTo(before);
	}

	@Test
	void newPasswordFollowsTheSignUpRules() throws Exception {
		mvc.perform(post("/account/password").with(csrf()).with(asha()))
			.andExpect(model().attributeHasFieldErrors("passwordForm", "currentPassword", "newPassword", "confirmPassword"));
		mvc.perform(post("/account/password").with(csrf()).with(asha())
				.param("currentPassword", "Masala2026").param("newPassword", "onlyletters").param("confirmPassword", "onlyletters"))
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "newPassword", "Pattern"));
		mvc.perform(post("/account/password").with(csrf()).with(asha())
				.param("currentPassword", "Masala2026").param("newPassword", "Kolhapuri99").param("confirmPassword", "Kolhapuri98"))
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "confirmPassword", "mismatch"));
		mvc.perform(post("/account/password").with(csrf()).with(asha())
				.param("currentPassword", "Masala2026").param("newPassword", "Masala2026").param("confirmPassword", "Masala2026"))
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "newPassword", "same"));
		String tooLong = "a1".repeat(37);
		mvc.perform(post("/account/password").with(csrf()).with(asha())
				.param("currentPassword", "Masala2026").param("newPassword", tooLong).param("confirmPassword", tooLong))
			.andExpect(model().attributeHasFieldErrorCode("passwordForm", "newPassword", "tooLong"));
		assertThat(passwordEncoder.matches("Masala2026", reload().getPasswordHash())).isTrue();
	}

}
