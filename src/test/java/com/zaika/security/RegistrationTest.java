package com.zaika.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/** Customer sign-up: the form, every validation rule, duplicate emails, and auto-login with a welcome toast. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RegistrationTest {

	static final String EMAIL = "asha.patil@register-test.zaika.test";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@AfterEach
	void deleteAccounts() {
		users.findAll().stream()
			.filter(u -> u.getEmail().endsWith("@register-test.zaika.test"))
			.forEach(users::delete);
	}

	@Test
	void signUpPageHasEveryFieldAndNoInlineScript() throws Exception {
		String html = mvc.perform(get("/register"))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/register"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html)
			.contains("<title>Create your account | Zaika</title>", "<h1 id=\"auth-title\">Create your account</h1>")
			.containsPattern("<form class=\"auth-form\" action=\"/register\" method=\"post\" novalidate>\\s*<input type=\"hidden\" name=\"_csrf\"")
			.containsPattern("<label class=\"field-label\" for=\"name\">Full name</label>\\s*<input class=\"input\" type=\"text\" id=\"name\" name=\"name\" value=\"\" autocomplete=\"name\">")
			.containsPattern("<input class=\"input\" type=\"email\" id=\"email\" name=\"email\" value=\"\" autocomplete=\"email\" aria-describedby=\"email-help\">")
			.containsPattern("<input class=\"input\" type=\"tel\" id=\"phone\" name=\"phone\" value=\"\" autocomplete=\"tel-national\" aria-describedby=\"phone-help\">")
			.contains("<input class=\"input\" type=\"password\" id=\"password\" name=\"password\" autocomplete=\"new-password\" aria-describedby=\"password-help\">",
					"<button type=\"button\" class=\"password-toggle\" data-password-toggle=\"password\" aria-controls=\"password\" hidden>",
					"<input class=\"input\" type=\"password\" id=\"confirmPassword\" name=\"confirmPassword\" autocomplete=\"new-password\">",
					"data-password-toggle=\"confirmPassword\"", "<span class=\"visually-hidden\"> confirm password</span>",
					"<input class=\"checkbox\" type=\"checkbox\" id=\"acceptTerms\" name=\"acceptTerms\" value=\"true\">",
					"href=\"/terms\"", "href=\"/privacy\"",
					"<button type=\"submit\" class=\"btn btn-primary btn-lg btn-block\">Create account</button>",
					"href=\"/login\">Sign in</a>")
			.doesNotContain("role=\"alert\"", "aria-invalid", "field-error")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
			.doesNotContainPattern("\\son[a-z]+=");
	}

	@Test
	void validSignUpCreatesACustomerAndSignsThemIn() throws Exception {
		MvcResult result = mvc.perform(register("email", "  Asha.Patil@Register-Test.Zaika.Test ", "phone", "+91 98765 43210"))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("flashSuccess", "Welcome to Zaika, Asha! Your account is ready."))
			.andExpect(authenticated().withUsername(EMAIL).withRoles("CUSTOMER"))
			.andReturn();

		User saved = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		assertThat(saved.getName()).isEqualTo("Asha Patil");
		assertThat(saved.getEmail()).isEqualTo(EMAIL);
		assertThat(saved.getPhone()).isEqualTo("9876543210");
		assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
		assertThat(saved.isEnabled()).isTrue();
		assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("Masala2026");
		assertThat(passwordEncoder.matches("Masala2026", saved.getPasswordHash())).isTrue();

		// the session now carries the sign-in: the customer area lets them in, other areas do not
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession();
		mvc.perform(get("/account").session(session))
			.andExpect(r -> assertThat(r.getResponse().getStatus()).isNotIn(302, 401, 403))
			.andExpect(authenticated().withUsername(EMAIL).withRoles("CUSTOMER"));
		mvc.perform(get("/owner").session(session)).andExpect(status().isForbidden());

		// and the account works with the login form too
		mvc.perform(post("/login").param("email", EMAIL).param("password", "Masala2026").with(csrf()))
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void welcomeToastRendersInTheLayout() throws Exception {
		MvcResult result = mvc.perform(register()).andReturn();
		String html = mvc.perform(get("/about").flashAttrs(result.getFlashMap()))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("toast-success", "Welcome to Zaika, Asha! Your account is ready.");
	}

	@Test
	void signUpChangesTheSessionIdAndTheCsrfToken() throws Exception {
		MockHttpSession session = (MockHttpSession) mvc.perform(get("/register")).andReturn().getRequest().getSession();
		String before = session.getId();
		Object csrfBefore = session.getAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN");
		assertThat(csrfBefore).isNotNull();
		mvc.perform(register().session(session)).andExpect(redirectedUrl("/"));
		assertThat(session.getId()).isNotEqualTo(before);
		assertThat(session.getAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN"))
			.isNotSameAs(csrfBefore);
	}

	@Test
	void signUpReturnsToThePageThatAskedForASignIn() throws Exception {
		MockHttpSession session = (MockHttpSession) mvc.perform(get("/checkout"))
			.andExpect(redirectedUrl("/login"))
			.andReturn().getRequest().getSession();
		String target = mvc.perform(register().session(session))
			.andExpect(status().isFound())
			.andReturn().getResponse().getRedirectedUrl();
		assertThat(target).startsWith("http://localhost/checkout");
	}

	@Test
	void emptyFormShowsAnInlineErrorOnEveryField() throws Exception {
		String html = mvc.perform(post("/register").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/register"))
			.andExpect(model().attributeHasFieldErrors("form", "name", "email", "phone", "password", "confirmPassword", "acceptTerms"))
			.andExpect(unauthenticated())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html)
			.contains("<div class=\"alert alert-error\" role=\"alert\">", "A few details need another look.")
			.contains("Tell us your name.", "Enter your email address.", "Enter your mobile number.",
					"Use 8 or more characters with at least one letter and one digit.", "Type your password again.",
					"Please accept the terms to create your account.")
			.contains("<input class=\"input\" type=\"text\" id=\"name\" name=\"name\" value=\"\" autocomplete=\"name\" aria-invalid=\"true\" aria-describedby=\"name-error\">")
			.contains("aria-describedby=\"email-error email-help\"", "id=\"acceptTerms-error\"",
					"aria-invalid=\"true\" aria-describedby=\"acceptTerms-error\"");
		assertThat(users.findAll()).noneMatch(u -> u.getEmail().endsWith("@register-test.zaika.test"));
	}

	@Test
	void anEmptyMobileFieldShowsOnlyTheRequiredMessage() throws Exception {
		String html = mvc.perform(post("/register").with(csrf()).param("phone", " "))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Enter your mobile number.").doesNotContain("Enter a 10-digit mobile number");
	}

	@Test
	void passwordRules() throws Exception {
		assertRejected(register("password", "abc12", "confirmPassword", "abc12"), "password",
				"Use 8 or more characters with at least one letter and one digit.");
		assertRejected(register("password", "onlyletters", "confirmPassword", "onlyletters"), "password",
				"Use 8 or more characters with at least one letter and one digit.");
		assertRejected(register("password", "12345678", "confirmPassword", "12345678"), "password",
				"Use 8 or more characters with at least one letter and one digit.");
		String tooLong = "a1".repeat(37);
		assertRejected(register("password", tooLong, "confirmPassword", tooLong), "password",
				"That password is too long.");
		assertRejected(register("confirmPassword", "Masala2027"), "confirmPassword", "The passwords don&#39;t match.");
		// the smallest valid password goes through
		mvc.perform(register("password", "abcdefg1", "confirmPassword", "abcdefg1"))
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void emailPhoneNameAndTermsRules() throws Exception {
		assertRejected(register("email", "asha.patil"), "email", "Enter a valid email address, like asha@example.com.");
		assertRejected(register("phone", "5876543210"), "phone", "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.");
		assertRejected(register("phone", "98765 4321"), "phone", "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.");
		assertRejected(register("name", "   "), "name", "Tell us your name.");
		assertRejected(register("name", "A".repeat(81)), "name", "Keep your name under 80 characters.");
		assertRejected(register("acceptTerms", "false"), "acceptTerms", "Please accept the terms to create your account.");
	}

	@Test
	void typedValuesAreKeptButPasswordsAreNot() throws Exception {
		String html = mvc.perform(register("acceptTerms", "false", "phone", "098765 43210"))
			.andExpect(view().name("auth/register"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html)
			.contains("name=\"name\" value=\"Asha Patil\"", "name=\"email\" value=\"" + EMAIL + "\"",
					"name=\"phone\" value=\"9876543210\"")
			.doesNotContain("Masala2026");
	}

	@Test
	void duplicateEmailInAnyCaseShowsAnInlineError() throws Exception {
		User existing = users.save(new User("Ravi Jadhav", EMAIL, "9123456780", passwordEncoder.encode("Other1234"), Role.RESTAURANT_OWNER));
		String html = mvc.perform(register("email", EMAIL.toUpperCase()))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/register"))
			.andExpect(model().attributeHasFieldErrorCode("form", "email", "taken"))
			.andExpect(unauthenticated())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("id=\"email-error\"", "This email already has a Zaika account. Sign in instead.");
		User unchanged = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		assertThat(unchanged.getId()).isEqualTo(existing.getId());
		assertThat(unchanged.getName()).isEqualTo("Ravi Jadhav");
		assertThat(unchanged.getRole()).isEqualTo(Role.RESTAURANT_OWNER);
		assertThat(users.findAll()).filteredOn(u -> u.getEmail().equals(EMAIL)).hasSize(1);
	}

	@Test
	void signUpWithoutCsrfIsRejected() throws Exception {
		mvc.perform(post("/register").param("name", "Asha Patil").param("email", EMAIL).param("phone", "9876543210")
				.param("password", "Masala2026").param("confirmPassword", "Masala2026").param("acceptTerms", "true"))
			.andExpect(status().isForbidden());
		assertThat(users.findByEmailIgnoreCase(EMAIL)).isEmpty();
	}

	@Test
	void signedInVisitorsSkipTheSignUpPage() throws Exception {
		mvc.perform(get("/register").with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(redirectedUrl("/"));
		mvc.perform(get("/register").with(user("rider@zaika.test").roles("DELIVERY_PARTNER")))
			.andExpect(redirectedUrl("/partner"));
		mvc.perform(register().with(user("owner@zaika.test").roles("RESTAURANT_OWNER")))
			.andExpect(redirectedUrl("/owner"));
		assertThat(users.findByEmailIgnoreCase(EMAIL)).isEmpty();
	}

	@Test
	void phoneNumbersAreNormalized() {
		assertThat(User.normalizePhone("+91 98765 43210")).isEqualTo("9876543210");
		assertThat(User.normalizePhone("+91-98765-43210")).isEqualTo("9876543210");
		assertThat(User.normalizePhone("919876543210")).isEqualTo("9876543210");
		assertThat(User.normalizePhone("09876543210")).isEqualTo("9876543210");
		assertThat(User.normalizePhone("(98765) 43210")).isEqualTo("9876543210");
		assertThat(User.normalizePhone("9876543210")).isEqualTo("9876543210");
		assertThat(User.normalizePhone("98765")).isEqualTo("98765");
		assertThat(User.normalizePhone(null)).isNull();
	}

	/** A complete, valid sign-up; tests pass name/value pairs that replace a default to break one rule. */
	private static MockHttpServletRequestBuilder register(String... overrides) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("name", "Asha Patil");
		params.put("email", EMAIL);
		params.put("phone", "9876543210");
		params.put("password", "Masala2026");
		params.put("confirmPassword", "Masala2026");
		params.put("acceptTerms", "true");
		for (int i = 0; i < overrides.length; i += 2) {
			params.put(overrides[i], overrides[i + 1]);
		}
		MockHttpServletRequestBuilder request = post("/register").with(csrf());
		params.forEach(request::param);
		return request;
	}

	private void assertRejected(MockHttpServletRequestBuilder request, String field, String message) throws Exception {
		String html = mvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(view().name("auth/register"))
			.andExpect(model().attributeHasFieldErrors("form", field))
			.andExpect(model().errorCount(1))
			.andExpect(unauthenticated())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("id=\"" + field + "-error\"", message);
		assertThat(users.findByEmailIgnoreCase(EMAIL)).isEmpty();
	}

}
