package com.zaika.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/** The sign-in page, form login against real accounts, role landing pages, remember-me and logout. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LoginTest {

	static final String PASSWORD = "Demo@1234";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ZaikaUserDetailsService userDetailsService;

	private final List<User> created = new ArrayList<>();

	@AfterEach
	void deleteAccounts() {
		users.deleteAllById(created.stream().map(User::getId).toList());
	}

	@Test
	void loginPageHasEmailPasswordWithShowHideAndRememberMe() throws Exception {
		String html = mvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(view().name("auth/login"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html)
			.contains("<title>Sign in | Zaika</title>", "<h1 id=\"auth-title\">Welcome back</h1>")
			.containsPattern("<form class=\"auth-form\" action=\"/login\" method=\"post\">\\s*<input type=\"hidden\" name=\"_csrf\"")
			.containsPattern("<label class=\"field-label\" for=\"email\">Email</label>\\s*<input class=\"input\" id=\"email\" name=\"email\" type=\"email\" autocomplete=\"username\"")
			.containsPattern("<input class=\"input\" id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\" required>")
			// the toggle is hidden until js/password-toggle.js wires it up, so it never shows dead without JavaScript
			.contains("<button type=\"button\" class=\"password-toggle\" data-password-toggle=\"password\" aria-controls=\"password\" hidden><span data-password-toggle-label>Show</span><span class=\"visually-hidden\"> password</span></button>")
			.contains("<input class=\"checkbox\" type=\"checkbox\" name=\"remember-me\" id=\"remember-me\">", "Keep me signed in")
			.contains("<button type=\"submit\" class=\"btn btn-primary btn-lg btn-block\">Sign in</button>")
			.contains("href=\"/forgot-password\">Forgot password?</a>", "href=\"/register\">Create an account</a>",
					"href=\"/partner-with-us\"", "<script defer src=\"/js/password-toggle.js\"></script>")
			.doesNotContain("role=\"alert\"", "aria-invalid")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
			.doesNotContainPattern("\\son[a-z]+=");
	}

	@Test
	void passwordToggleScriptIsSmallAndServed() throws Exception {
		String js = mvc.perform(get("/js/password-toggle.js")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(js).contains("[data-password-toggle]", "button.hidden = false", "input.type = show ? 'text' : 'password'");
		assertThat(js.lines().count()).isLessThan(60);
	}

	@Test
	void eachRoleLandsOnItsOwnPageAfterSignIn() throws Exception {
		assertLandsOn(account(Role.CUSTOMER), "/");
		assertLandsOn(account(Role.RESTAURANT_OWNER), "/owner");
		assertLandsOn(account(Role.DELIVERY_PARTNER), "/partner");
		assertLandsOn(account(Role.ADMIN), "/admin");
	}

	@Test
	void emailIsCaseInsensitive() throws Exception {
		User owner = account(Role.RESTAURANT_OWNER);
		mvc.perform(login("  " + owner.getEmail().toUpperCase() + " ", PASSWORD))
			.andExpect(redirectedUrl("/owner"))
			.andExpect(authenticated().withUsername(owner.getEmail()).withRoles("RESTAURANT_OWNER"));
	}

	@Test
	void wrongPasswordShowsAnErrorAndKeepsTheEmail() throws Exception {
		User customer = account(Role.CUSTOMER);
		MvcResult failed = mvc.perform(login(customer.getEmail(), "Wrong@1234"))
			.andExpect(redirectedUrl("/login?error"))
			.andExpect(unauthenticated())
			.andReturn();
		String html = mvc.perform(get("/login?error").session((MockHttpSession) failed.getRequest().getSession()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html)
			.contains("<div class=\"alert alert-error\" role=\"alert\" id=\"login-error\">", "That email and password don't match.")
			.containsPattern("id=\"email\"[^>]*value=\"" + customer.getEmail() + "\" aria-invalid=\"true\" aria-describedby=\"login-error\"")
			.doesNotContain("You're signed out");
	}

	@Test
	void onlyOneErrorAlertRendersAndBothFieldsPointAtIt() throws Exception {
		String html = mvc.perform(get("/login?error&locked&disabled"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html.split("id=\"login-error\"", -1)).as("one element with the id").hasSize(2);
		assertThat(html).contains("Too many wrong passwords.")
			.doesNotContain("That email and password don't match.", "This account is switched off.")
			.containsPattern("id=\"email\"[^>]*aria-describedby=\"login-error\"")
			.containsPattern("id=\"password\"[^>]*aria-invalid=\"true\" aria-describedby=\"login-error\"");
	}

	@Test
	void unknownEmailGetsTheSameAnswerAsAWrongPassword()throws Exception {
		mvc.perform(login("nobody@zaika.test", PASSWORD))
			.andExpect(redirectedUrl("/login?error"))
			.andExpect(unauthenticated());
	}

	@Test
	void disabledAccountsAreRejectedButOnlyRevealedWithTheRightPassword() throws Exception {
		User rider = account(Role.DELIVERY_PARTNER);
		rider.setEnabled(false);
		users.save(rider);

		mvc.perform(login(rider.getEmail(), PASSWORD))
			.andExpect(redirectedUrl("/login?disabled"))
			.andExpect(unauthenticated());
		mvc.perform(login(rider.getEmail(), "Wrong@1234"))
			.andExpect(redirectedUrl("/login?error"))
			.andExpect(unauthenticated());
		assertThat(mvc.perform(get("/login?disabled")).andReturn().getResponse().getContentAsString())
			.contains("This account is switched off.", "href=\"mailto:support@zaika.test\"");
	}

	@Test
	void signInReturnsToThePageThatAskedForIt() throws Exception {
		User customer = account(Role.CUSTOMER);
		MockHttpSession session = (MockHttpSession) mvc.perform(get("/orders/ZK-26-000123"))
			.andExpect(redirectedUrl("/login"))
			.andReturn().getRequest().getSession();
		String target = mvc.perform(login(customer.getEmail(), PASSWORD)
				.session(session))
			.andExpect(status().isFound())
			.andReturn().getResponse().getRedirectedUrl();
		assertThat(target).startsWith("http://localhost/orders/ZK-26-000123");
	}

	@Test
	void savedPageInAnotherRolesAreaFallsBackToTheLandingPage() throws Exception {
		User customer = account(Role.CUSTOMER);
		MockHttpSession session = (MockHttpSession) mvc.perform(get("/admin/restaurants"))
			.andExpect(redirectedUrl("/login"))
			.andReturn().getRequest().getSession();
		mvc.perform(login(customer.getEmail(), PASSWORD).session(session))
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void htmxFragmentsAndWellKnownPathsAreNotSavedAsTheSignInTarget() throws Exception {
		User customer = account(Role.CUSTOMER);
		for (MockHttpServletRequestBuilder denied : List.of(get("/account/addresses").header("HX-Request", "true"),
				get("/.well-known/change-password"))) {
			MockHttpSession session = (MockHttpSession) mvc.perform(denied)
				.andExpect(redirectedUrl("/login"))
				.andReturn().getRequest().getSession();
			mvc.perform(login(customer.getEmail(), PASSWORD).session(session))
				.andExpect(redirectedUrl("/"));
		}
	}

	@Test
	void signedInVisitorsSkipTheLoginPage()throws Exception {
		mvc.perform(get("/login").with(user("owner@zaika.test").roles("RESTAURANT_OWNER")))
			.andExpect(redirectedUrl("/owner"));
		mvc.perform(get("/login").with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void rememberMeKeepsTheCustomerSignedInWithoutASession() throws Exception {
		User customer = account(Role.CUSTOMER);
		Cookie rememberMe = mvc.perform(login(customer.getEmail(), PASSWORD)
				.param("remember-me", "on"))
			.andExpect(redirectedUrl("/"))
			.andReturn().getResponse().getCookie("remember-me");
		assertThat(rememberMe).isNotNull();
		assertThat(rememberMe.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);
		assertThat(rememberMe.isHttpOnly()).isTrue();

		// a fresh request with only the cookie is signed in
		mvc.perform(get("/account").cookie(rememberMe))
			.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(302, 401, 403))
			.andExpect(authenticated().withUsername(customer.getEmail()).withRoles("CUSTOMER"));

		// switching the account off makes the cookie worthless
		customer.setEnabled(false);
		users.save(customer);
		mvc.perform(get("/account").cookie(rememberMe))
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void withoutRememberMeNoCookieIsSet() throws Exception {
		User customer = account(Role.CUSTOMER);
		MvcResult result = mvc.perform(login(customer.getEmail(), PASSWORD))
			.andExpect(redirectedUrl("/"))
			.andReturn();
		assertThat(result.getResponse().getCookie("remember-me")).isNull();
	}

	@Test
	void logoutIsAPostWithCsrfAndShowsAMessage() throws Exception {
		mvc.perform(post("/logout").with(csrf()).with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(redirectedUrl("/login?logout"))
			.andExpect(unauthenticated());
		mvc.perform(post("/logout").with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(status().isForbidden());
		mvc.perform(post("/logout").with(csrf().useInvalidToken()).with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(status().isForbidden());
		assertThat(mvc.perform(get("/login?logout")).andReturn().getResponse().getContentAsString())
			.contains("<div class=\"alert alert-success\" role=\"status\">", "You're signed out.");
	}

	@Test
	void loginPostWithoutCsrfIsRejected() throws Exception {
		User customer = account(Role.CUSTOMER);
		mvc.perform(post("/login").param("email", customer.getEmail()).param("password", PASSWORD))
			.andExpect(status().isForbidden())
			.andExpect(unauthenticated());
	}

	@Test
	void userDetailsServiceLoadsByEmailWithTheRoleAuthority() {
		User admin = account(Role.ADMIN);
		UserDetails details = userDetailsService.loadUserByUsername(admin.getEmail().toUpperCase());
		assertThat(details.getUsername()).isEqualTo(admin.getEmail());
		assertThat(details.getPassword()).isEqualTo(admin.getPasswordHash()).startsWith("$2");
		assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
		assertThat(details.isEnabled()).isTrue();
		assertThatThrownBy(() -> userDetailsService.loadUserByUsername("ghost@zaika.test"))
			.isInstanceOf(UsernameNotFoundException.class);
	}

	/** The sign-in form as a browser posts it. */
	private static MockHttpServletRequestBuilder login(String email, String password) {
		return post("/login").param("email", email).param("password", password).with(csrf());
	}

	private void assertLandsOn(User account, String landing) throws Exception {
		mvc.perform(login(account.getEmail(), PASSWORD))
			.andExpect(redirectedUrl(landing))
			.andExpect(authenticated().withRoles(account.getRole().name()));
	}

	private User account(Role role) {
		String email = role.name().toLowerCase().replace('_', '-') + created.size() + "@login-test.zaika.test";
		User user = users.save(new User("Asha Patil", email, "9876543210", passwordEncoder.encode(PASSWORD), role));
		created.add(user);
		return user;
	}

}
