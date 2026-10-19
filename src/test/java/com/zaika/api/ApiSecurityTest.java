package com.zaika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.zaika.security.ApiAuthService;
import com.zaika.support.MutableClock;
import com.zaika.user.PasswordChangeForm;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;
import com.zaika.user.UserService;

/**
 * The REST API's own security chain: JWT sign-up and sign-in, {@code /api/v1/me}, problem-detail errors, and the
 * website's chain left as it was. Clock: Sunday 4 Oct 2026, 19:30 IST (14:00 UTC).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiSecurityTest {

	static final String PASSWORD = "Demo@1234";

	static final String WRONG = "Wrong@1234";

	static final String DOMAIN = "@api-security.zaika.test";

	static final String EMAIL = "ananya.patil" + DOMAIN;

	static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	MutableClock clock;

	@Autowired
	UserService userService;

	@AfterEach
	void cleanUp() {
		users.findAll().stream().filter(u -> u.getEmail().endsWith(DOMAIN)).forEach(users::delete);
		clock.reset();
	}

	// --- sign-up

	@Test
	void signUpCreatesACustomerAndAnswersWithAWorkingToken() throws Exception {
		MvcResult result = mvc.perform(json(post("/api/v1/auth/register"), """
				{"name": "  Ananya Patil ", "email": "Ananya.Patil@API-security.zaika.test", "phone": "+91 98220 12345",
				 "password": "Kolhapur@2026", "acceptTerms": true}"""))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/me"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(Duration.ofDays(7).toSeconds()))
			.andExpect(jsonPath("$.expiresAt").value("2026-10-11T14:00:00Z"))
			.andExpect(jsonPath("$.user.name").value("Ananya Patil"))
			.andExpect(jsonPath("$.user.email").value(EMAIL))
			.andExpect(jsonPath("$.user.phone").value("9822012345"))
			.andExpect(jsonPath("$.user.role").value("CUSTOMER"))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("passwordHash")
			.doesNotContain("$2a$");
		assertThat(result.getRequest().getSession(false)).as("stateless: no session").isNull();
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

		User saved = users.findByEmail(EMAIL).orElseThrow();
		assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
		assertThat(passwordEncoder.matches("Kolhapur@2026", saved.getPasswordHash())).isTrue();

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(result)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(saved.getId()))
			.andExpect(jsonPath("$.email").value(EMAIL))
			.andExpect(jsonPath("$.role").value("CUSTOMER"));
	}

	@Test
	void signUpUsesTheWebsiteRulesAndNamesEveryBadField() throws Exception {
		mvc.perform(json(post("/api/v1/auth/register"), "{}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.title").value("Bad Request"))
			.andExpect(jsonPath("$.detail").value("Some fields need fixing. See errors for each field."))
			.andExpect(jsonPath("$.instance").value("/api/v1/auth/register"))
			.andExpect(jsonPath("$.errors.name").value("Tell us your name."))
			.andExpect(jsonPath("$.errors.email").value("Enter your email address."))
			.andExpect(jsonPath("$.errors.phone").value("Enter your mobile number."))
			.andExpect(jsonPath("$.errors.password").value("Use 8 or more characters with at least one letter and one digit."))
			.andExpect(jsonPath("$.errors.acceptTerms").value("Please accept the terms to create your account."))
			.andExpect(jsonPath("$.errors.confirmPassword").doesNotExist());

		mvc.perform(json(post("/api/v1/auth/register"), """
				{"name": "Ananya", "email": "not-an-email", "phone": "12345", "password": "%s", "acceptTerms": true}"""
			.formatted("a1".repeat(37))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.email").value("Enter a valid email address, like asha@example.com."))
			.andExpect(jsonPath("$.errors.phone").value("Enter a 10-digit mobile number starting with 6, 7, 8 or 9."))
			.andExpect(jsonPath("$.errors.password").value("That password is too long."))
			.andExpect(jsonPath("$.errors.name").doesNotExist());
		assertThat(users.findAll()).noneMatch(u -> u.getEmail().endsWith(DOMAIN));
	}

	@Test
	void signUpWithATakenEmailInAnyCaseIsAConflict() throws Exception {
		account(Role.RESTAURANT_OWNER);
		mvc.perform(json(post("/api/v1/auth/register"), """
				{"name": "Someone Else", "email": "ANANYA.PATIL@api-security.zaika.test", "phone": "9822012346",
				 "password": "Kolhapur@2026", "acceptTerms": true}"""))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail").value("This email already has a Zaika account. Sign in instead."))
			.andExpect(jsonPath("$.errors.email").value("This email already has a Zaika account. Sign in instead."));
		assertThat(users.findByEmail(EMAIL).orElseThrow().getRole()).isEqualTo(Role.RESTAURANT_OWNER);
	}

	@Test
	void unreadableOrWrongTypeBodiesAreProblemsToo() throws Exception {
		mvc.perform(json(post("/api/v1/auth/login"), "{\"email\": "))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail").value("The request body is missing or is not valid JSON."));
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.status").value(415));
		mvc.perform(json(post("/api/v1/auth/login"), "{\"email\": \" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.email").value("Enter your email address."))
			.andExpect(jsonPath("$.errors.password").value("Enter your password."));
	}

	// --- sign-in

	@Test
	void signInAnswersWithATokenForMe() throws Exception {
		User user = account(Role.DELIVERY_PARTNER);
		MvcResult result = mvc.perform(login("  Ananya.Patil@API-security.zaika.test ", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.user.id").value(user.getId()))
			.andExpect(jsonPath("$.user.role").value("DELIVERY_PARTNER"))
			.andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();

		MvcResult me = mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(result)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.id").value(user.getId()))
			.andExpect(jsonPath("$.name").value("Ananya Patil"))
			.andExpect(jsonPath("$.email").value(EMAIL))
			.andExpect(jsonPath("$.phone").value("9822012345"))
			.andExpect(jsonPath("$.role").value("DELIVERY_PARTNER"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
			.andReturn();
		assertThat(me.getResponse().getContentAsString()).doesNotContain("$2a$", "password");
		assertThat(me.getRequest().getSession(false)).as("no session, so no guest cart either").isNull();
	}

	@Test
	void wrongPasswordsCountTowardsTheSameLockAsTheWebsite() throws Exception {
		account(Role.CUSTOMER);
		String wrong = "That email and password don't match. Check both and try again.";
		mvc.perform(login("nobody" + DOMAIN, WRONG))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail").value(wrong));
		for (int i = 1; i < UserService.MAX_FAILED_LOGINS; i++) {
			mvc.perform(login(EMAIL, WRONG)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(wrong));
		}
		assertThat(users.findByEmail(EMAIL).orElseThrow().getFailedLoginCount()).isEqualTo(4);

		mvc.perform(login(EMAIL, WRONG))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
			.andExpect(jsonPath("$.lockedUntil").value("2026-10-04T19:45:00+05:30"))
			.andExpect(jsonPath("$.detail").value("Too many wrong passwords. To keep your account safe, sign-in is paused "
					+ "until 7:45 PM. Reset your password on the website to get back in right away."));

		// the right password is refused during the lock, on the API and on the website alike
		clock.advance(Duration.ofMinutes(10));
		mvc.perform(login(EMAIL, PASSWORD))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"));
		mvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
			.andExpect(redirectedUrl("/login?locked"));

		clock.advance(Duration.ofMinutes(5));
		mvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk());
		assertThat(users.findByEmail(EMAIL).orElseThrow().getFailedLoginCount()).isZero();
	}

	@Test
	void aSwitchedOffAccountIsToldSoOnlyAfterTheRightPassword() throws Exception {
		User user = account(Role.CUSTOMER);
		user.setEnabled(false);
		users.save(user);
		mvc.perform(login(EMAIL, WRONG))
			.andExpect(status().isUnauthorized());
		mvc.perform(login(EMAIL, PASSWORD))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail")
				.value("This account is switched off. Write to support@zaika.test and we'll help you get back in."));
	}

	// --- tokens

	@Test
	void withoutAValidTokenTheAnswerIs401WithABearerChallenge() throws Exception {
		mvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.title").value("Unauthorized"))
			.andExpect(jsonPath("$.instance").value("/api/v1/me"))
			.andExpect(jsonPath("$.detail")
				.value("Sign in to use this. Send your access token as \"Authorization: Bearer <token>\"."));

		for (String token : List.of("not-a-token", "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.", forgedToken())) {
			mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.detail").value("Your access token is not valid any more. Sign in again."));
		}
	}

	@Test
	void tokensExpireAfterSevenDays() throws Exception {
		account(Role.CUSTOMER);
		String token = token(mvc.perform(login(EMAIL, PASSWORD)).andReturn());
		clock.advance(ApiAuthService.TOKEN_LIFETIME.minusMinutes(1));
		mvc.perform(bearer(get("/api/v1/me"), token)).andExpect(status().isOk());
		clock.advance(Duration.ofMinutes(3)); // past expiry and Spring's one-minute clock skew
		mvc.perform(bearer(get("/api/v1/me"), token))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Your access token is not valid any more. Sign in again."));
	}

	@Test
	void tokensStopWorkingWhenTheAccountIsSwitchedOffOrThePasswordChanges() throws Exception {
		User user = account(Role.CUSTOMER);
		String first = token(mvc.perform(login(EMAIL, PASSWORD)).andReturn());

		user = users.findByEmail(EMAIL).orElseThrow();
		user.setEnabled(false);
		users.save(user);
		mvc.perform(bearer(get("/api/v1/me"), first)).andExpect(status().isUnauthorized());

		user = users.findByEmail(EMAIL).orElseThrow();
		user.setEnabled(true);
		users.save(user);
		mvc.perform(bearer(get("/api/v1/me"), first)).andExpect(status().isOk());

		PasswordChangeForm change = new PasswordChangeForm();
		change.setCurrentPassword(PASSWORD);
		change.setNewPassword("NewPass@2026");
		change.setConfirmPassword("NewPass@2026");
		userService.changePassword(user.getId(), change);
		mvc.perform(bearer(get("/api/v1/me"), first))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Your access token is not valid any more. Sign in again."));
		mvc.perform(bearer(get("/api/v1/me"), token(mvc.perform(login(EMAIL, "NewPass@2026")).andReturn())))
			.andExpect(status().isOk());
	}

	@Test
	void roleAreasAnswer403AndUnknownAddresses404AsProblems() throws Exception {
		account(Role.CUSTOMER);
		String customer = token(mvc.perform(login(EMAIL, PASSWORD)).andReturn());
		for (String area : List.of("/api/v1/owner/orders", "/api/v1/rider/orders")) {
			mvc.perform(bearer(get(area), customer))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"insufficient_scope\"")))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.detail").value("Your account can't use this."));
		}
		mvc.perform(get("/api/v1/no-such-thing")).andExpect(status().isUnauthorized());
		mvc.perform(bearer(get("/api/v1/no-such-thing"), customer))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail").value("There is no API endpoint at this address."))
			.andExpect(jsonPath("$.instance").value("/api/v1/no-such-thing"));

		users.delete(users.findByEmail(EMAIL).orElseThrow());
		account(Role.RESTAURANT_OWNER);
		String owner = token(mvc.perform(login(EMAIL, PASSWORD)).andReturn());
		mvc.perform(bearer(get("/api/v1/owner/orders"), owner)).andExpect(status().isNotFound());
	}

	// --- the website's chain is unchanged

	@Test
	void tokensAndSessionsNeverCrossBetweenTheWebsiteAndTheApi() throws Exception {
		account(Role.CUSTOMER);
		String token = token(mvc.perform(login(EMAIL, PASSWORD)).andReturn());
		// a bearer token does not sign anyone in on the website
		mvc.perform(bearer(get("/account"), token)).andExpect(redirectedUrl("/login"));
		// web forms still need their CSRF token
		mvc.perform(post("/login").param("email", EMAIL).param("password", PASSWORD)).andExpect(status().isForbidden());

		// a website session does not sign anyone in on the API
		MvcResult web = mvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
			.andExpect(redirectedUrl("/"))
			.andReturn();
		MockHttpSession session = (MockHttpSession) web.getRequest().getSession(false);
		mvc.perform(get("/account").session(session)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
	}

	// --- helpers

	private User account(Role role) {
		return users.save(new User("Ananya Patil", EMAIL, "9822012345", passwordEncoder.encode(PASSWORD), role));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static MockHttpServletRequestBuilder login(String email, String password) {
		return json(post("/api/v1/auth/login"), "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password));
	}

	private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private static String token(MvcResult result) throws Exception {
		String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		assertThat(token).isNotBlank();
		return token;
	}

	/** A well-formed token with this app's issuer and claims, signed with some other RSA key. */
	private static String forgedToken() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keys = generator.generateKeyPair();
		Instant now = MutableClock.DEFAULT_NOW.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("http://localhost:8080")
			.subject("1")
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofDays(1)))
			.claim("role", "ADMIN")
			.build();
		return NimbusJwtEncoder.withKeyPair((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate())
			.build()
			.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
			.getTokenValue();
	}

}
