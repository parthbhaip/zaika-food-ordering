package com.zaika.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.zaika.support.MutableClock;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;
import com.zaika.user.UserService;

/** Five wrong passwords lock password sign-in for 15 minutes; the right password resets the count. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LoginLockoutTest {

	static final String PASSWORD = "Demo@1234";

	static final String WRONG = "Wrong@1234";

	static final String EMAIL = "meera.kulkarni@lockout-test.zaika.test";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void createAccount() {
		users.save(new User("Meera Kulkarni", EMAIL, "9822012345", passwordEncoder.encode(PASSWORD), Role.CUSTOMER));
	}

	@AfterEach
	void cleanUp() {
		users.findByEmail(EMAIL).ifPresent(users::delete);
		clock.reset();
	}

	@Test
	void fifthWrongPasswordLocksSignInForFifteenMinutes() throws Exception {
		for (int i = 1; i < UserService.MAX_FAILED_LOGINS; i++) {
			mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
		}
		assertThat(account().getFailedLoginCount()).isEqualTo(4);
		assertThat(account().getLockedUntil()).isNull();

		MvcResult locked = mvc.perform(login(EMAIL, WRONG))
			.andExpect(redirectedUrl("/login?locked"))
			.andExpect(unauthenticated())
			.andReturn();
		assertThat(account().getFailedLoginCount()).isEqualTo(5);
		assertThat(account().getLockedUntil()).isEqualTo(MutableClock.DEFAULT_NOW.plus(UserService.LOGIN_LOCK));
		assertThat(UserService.LOGIN_LOCK).isEqualTo(Duration.ofMinutes(15));

		// the friendly message names the unlock time (19:30 + 15 min) and offers the reset link
		String html = mvc.perform(get("/login?locked").session((MockHttpSession) locked.getRequest().getSession()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html)
			.contains("<div class=\"alert alert-error\" role=\"alert\" id=\"login-error\">", "Too many wrong passwords.",
					"sign-in is paused <span>until 7:45 PM</span>",
					"<a href=\"/forgot-password\">Reset your password</a> to get back in right away.")
			.containsPattern("id=\"email\"[^>]*value=\"" + EMAIL + "\" aria-invalid=\"true\" aria-describedby=\"login-error\"")
			.doesNotContain("That email and password don't match.");
	}

	@Test
	void whileLockedEvenTheRightPasswordIsRefusedAndAttemptsDoNotExtendTheLock() throws Exception {
		lockAccount();

		clock.advance(Duration.ofMinutes(14));
		mvc.perform(login(EMAIL, PASSWORD)).andExpect(redirectedUrl("/login?locked")).andExpect(unauthenticated());
		mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?locked")).andExpect(unauthenticated());
		assertThat(account().getFailedLoginCount()).isEqualTo(5);
		assertThat(account().getLockedUntil()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(15));

		clock.advance(Duration.ofMinutes(1));
		mvc.perform(login(EMAIL, PASSWORD))
			.andExpect(redirectedUrl("/"))
			.andExpect(authenticated().withUsername(EMAIL));
		assertThat(account().getFailedLoginCount()).isZero();
		assertThat(account().getLockedUntil()).isNull();
	}

	@Test
	void theRightPasswordResetsTheCount() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?error"));
		}
		mvc.perform(login(EMAIL, PASSWORD)).andExpect(redirectedUrl("/")).andExpect(authenticated());
		assertThat(account().getFailedLoginCount()).isZero();

		// four more wrong passwords are not five in a row
		for (int i = 0; i < 4; i++) {
			mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?error"));
		}
		assertThat(account().getLockedUntil()).isNull();
	}

	@Test
	void afterTheLockRunsOutTheCountStartsAgain() throws Exception {
		lockAccount();
		clock.advance(Duration.ofMinutes(15));

		mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?error"));
		assertThat(account().getFailedLoginCount()).isEqualTo(1);
		assertThat(account().getLockedUntil()).isNull();
	}

	@Test
	void emailCaseDoesNotMatterForTheCount() throws Exception {
		for (int i = 1; i < UserService.MAX_FAILED_LOGINS; i++) {
			mvc.perform(login(EMAIL.toUpperCase(), WRONG)).andExpect(redirectedUrl("/login?error"));
		}
		mvc.perform(login("  " + EMAIL + " ", WRONG)).andExpect(redirectedUrl("/login?locked"));
	}

	@Test
	void unknownEmailsAreNeverReportedAsLocked() throws Exception {
		for (int i = 0; i <= UserService.MAX_FAILED_LOGINS; i++) {
			mvc.perform(login("nobody@lockout-test.zaika.test", WRONG))
				.andExpect(redirectedUrl("/login?error"));
		}
	}

	@Test
	void lockedMessageWithoutTheFlashStillReadsWell() throws Exception {
		String html = mvc.perform(get("/login?locked")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("Too many wrong passwords.", "sign-in is paused <span>for a few minutes</span>");
	}

	@Test
	void theLockStopsPasswordSignInOnlyNotARememberedDevice() throws Exception {
		Cookie rememberMe = mvc.perform(login(EMAIL, PASSWORD).param("remember-me", "on"))
			.andExpect(redirectedUrl("/"))
			.andReturn().getResponse().getCookie("remember-me");
		assertThat(rememberMe).isNotNull();

		lockAccount();

		// someone guessing passwords cannot sign the owner out of their own phone
		mvc.perform(get("/account").cookie(rememberMe))
			.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(302, 401, 403))
			.andExpect(authenticated().withUsername(EMAIL));
	}

	private void lockAccount() throws Exception {
		for (int i = 1; i < UserService.MAX_FAILED_LOGINS; i++) {
			mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?error"));
		}
		mvc.perform(login(EMAIL, WRONG)).andExpect(redirectedUrl("/login?locked"));
	}

	private User account() {
		return users.findByEmail(EMAIL).orElseThrow();
	}

	private static MockHttpServletRequestBuilder login(String email, String password) {
		return post("/login").param("email", email).param("password", password).with(csrf());
	}

}
