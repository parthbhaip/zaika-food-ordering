package com.zaika.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

import com.zaika.user.Role;
import com.zaika.user.UserService;

/**
 * Web security: public pages and assets, members-only areas per role ({@link #AREAS}), the custom login page with
 * remember-me, POST logout and the security headers (CSP from CLAUDE.md) on every response. Anything not listed
 * needs a signed-in user, so new URLs are private until someone opens them up here.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; img-src 'self' data:; "
			+ "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

	/** The H2 console (dev only) is a frameset full of inline scripts. */
	public static final String H2_CONSOLE_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; "
			+ "style-src 'self' 'unsafe-inline'; frame-ancestors 'self'";

	static final String[] PUBLIC_PAGES = { "/", "/about", "/contact", "/terms", "/privacy", "/refund-policy",
			"/photo-credits", "/error", "/login", "/register", "/forgot-password", "/reset-password", "/restaurants/**",
			"/r/**", "/search/**", "/partner-with-us/**" };

	/** The API description and Swagger UI (springdoc): public, like the API's own catalog. */
	static final String[] API_DOCS = { "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**" };

	/** The cart is for guests and customers; staff accounts have none (CartController checks it again). */
	static final AuthorizationManager<RequestAuthorizationContext> GUEST_OR_CUSTOMER = AuthorizationManagers.anyOf(
			AuthenticatedAuthorizationManager.anonymous(), AuthorityAuthorizationManager.hasRole(Role.CUSTOMER.name()));

	static final String[] STATIC_ASSETS = { "/css/**", "/js/**", "/img/**", "/fonts/**", "/webjars/**", "/favicon.ico",
			"/uploads/**", "/placeholder/**" };

	/** Members-only areas: URL prefix (the prefix itself and everything below it) and the one role allowed in. */
	static final Map<String, Role> AREAS = Map.of(
			"/account", Role.CUSTOMER,
			"/checkout", Role.CUSTOMER,
			"/orders", Role.CUSTOMER,
			"/pay", Role.CUSTOMER,
			"/owner", Role.RESTAURANT_OWNER,
			"/partner", Role.DELIVERY_PARTNER,
			"/admin", Role.ADMIN);

	static final Duration REMEMBER_ME_VALIDITY = Duration.ofDays(30);

	/** The role whose area {@code path} (without context path) belongs to, or {@code null} for everything else. */
	static Role areaOf(String path) {
		for (Map.Entry<String, Role> area : AREAS.entrySet()) {
			if (path.equals(area.getKey()) || path.startsWith(area.getKey() + "/")) {
				return area.getValue();
			}
		}
		return null;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** Every signed-in session per account, so an admin can sign a disabled account out ({@link LiveSessions}). */
	@Bean
	SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}

	/** Tells the registry when sessions end or change id (Boot registers it as a servlet listener). */
	@Bean
	HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	@Bean
	@Order(1)
	@Profile("dev")
	SecurityFilterChain h2ConsoleSecurity(HttpSecurity http) throws Exception {
		http.securityMatcher("/h2-console/**")
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.headers(headers -> headers
				.frameOptions(frame -> frame.sameOrigin())
				.contentSecurityPolicy(csp -> csp.policyDirectives(H2_CONSOLE_POLICY)));
		return http.build();
	}

	/** The key that signs remember-me cookies: {@code app.remember-me-key}, or a random one per startup. */
	record RememberMeKey(String value) {
	}

	@Bean
	RememberMeKey rememberMeKey(@Value("${app.remember-me-key:}") String rememberMeKey) {
		return new RememberMeKey(rememberMeKey.isBlank() ? randomKey() : rememberMeKey);
	}

	/**
	 * Password sign-in (website form and API login) plus remember-me cookies. A sign-in lock (too many wrong
	 * passwords) rejects even the right password, before it is checked, so attempts during the lock are not counted.
	 * Other account status (disabled) is checked only after the password matched, so a wrong password never reveals
	 * whether an account exists or is switched off. The right password then wipes the failure count; the caller counts
	 * the failures ({@link LoginFailureHandler} on the website, {@link ApiAuthService} for the API).
	 */
	@Bean
	ProviderManager authenticationManager(ZaikaUserDetailsService users, PasswordEncoder passwordEncoder,
			UserService userService, Clock clock, RememberMeKey rememberMeKey) {
		AccountStatusUserDetailsChecker statusChecker = new AccountStatusUserDetailsChecker();
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(passwordEncoder);
		provider.setPreAuthenticationChecks(user -> {
			if (user instanceof CurrentUser current && current.isSignInLockedAt(LocalDateTime.now(clock))) {
				throw new LockedException("Sign-in locked after too many wrong passwords");
			}
		});
		provider.setPostAuthenticationChecks(user -> {
			statusChecker.check(user);
			if (user instanceof CurrentUser current) {
				userService.recordSuccessfulLogin(current.getId());
			}
		});
		return new ProviderManager(provider, new RememberMeAuthenticationProvider(rememberMeKey.value()));
	}

	@Bean
	@Order(2)
	SecurityFilterChain webSecurity(HttpSecurity http, ZaikaUserDetailsService users,
			ProviderManager authenticationManager, LoginSuccessHandler successHandler, UserService userService,
			SessionRegistry sessionRegistry, RememberMeKey rememberMeKey, Environment env) throws Exception {
		boolean dev = env.acceptsProfiles(Profiles.of("dev"));
		LoginUrlAuthenticationEntryPoint toLogin = new LoginUrlAuthenticationEntryPoint("/login");
		String key = rememberMeKey.value();

		// set explicitly, so it must also accept the tokens the remember-me filter hands it
		http.authenticationManager(authenticationManager)
			.authorizeHttpRequests(auth -> {
				// error pages render for everyone, so anonymous 404s and crashes show the branded page
				auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
					.requestMatchers(STATIC_ASSETS).permitAll()
					// the cart page's "Sign in to apply a coupon" link: sign-in leads back to the cart
					.requestMatchers("/cart/sign-in").hasRole(Role.CUSTOMER.name())
					.requestMatchers("/cart/**").access(GUEST_OR_CUSTOMER)
					.requestMatchers(PUBLIC_PAGES).permitAll()
					.requestMatchers(HttpMethod.GET, API_DOCS).permitAll();
				if (dev) {
					auth.requestMatchers("/dev/**").permitAll();
				}
				AREAS.forEach((prefix, role) -> auth.requestMatchers(prefix + "/**").hasRole(role.name()));
				auth.anyRequest().authenticated();
			})
			// htmx would follow the redirect and swap the whole login page into its target (a tracking poll, say):
			// htmx requests get 401 and HX-Redirect, which sends the browser itself to the login page
			.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, denied) -> {
				if (request.getHeader("HX-Request") == null) {
					toLogin.commence(request, response, denied);
					return;
				}
				response.setHeader("HX-Redirect", request.getContextPath() + "/login");
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			}))
			.formLogin(form -> form
				.loginPage("/login")
				.usernameParameter("email")
				.successHandler(successHandler)
				.failureHandler(new LoginFailureHandler(userService)))
			.requestCache(cache -> cache.requestCache(requestCache()))
			.rememberMe(remember -> remember
				.key(key)
				.userDetailsService(users)
				.tokenValiditySeconds((int) REMEMBER_ME_VALIDITY.toSeconds()))
			// every sign-in (form, remember-me) registers its session; one ended by LiveSessions (account disabled)
			// signs the browser out on its next request
			.sessionManagement(session -> session.maximumSessions(-1)
				.sessionRegistry(sessionRegistry)
				.expiredSessionStrategy(event -> {
					HttpServletResponse response = event.getResponse();
					String target = event.getRequest().getContextPath() + "/login?disabled";
					if (event.getRequest().getHeader("HX-Request") == null) {
						response.sendRedirect(target);
						return;
					}
					response.setHeader("HX-Redirect", target);
					response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				}))
			.logout(logout -> logout
				.logoutSuccessUrl("/login?logout")
				.deleteCookies("JSESSIONID"))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
		return http.build();
	}

	/**
	 * Which denied requests become the page to return to after sign-in. Spring's defaults (GET only; no favicon,
	 * XHR, JSON, upload or event-stream requests) plus: no htmx fragment requests (header HX-Request) and nothing
	 * under /.well-known/, so a customer never lands on a bare fragment or a probe URL.
	 */
	private static HttpSessionRequestCache requestCache() {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		MediaTypeRequestMatcher nonPages = new MediaTypeRequestMatcher(MediaType.APPLICATION_JSON,
				MediaType.MULTIPART_FORM_DATA, MediaType.TEXT_EVENT_STREAM);
		nonPages.setIgnoredMediaTypes(Set.of(MediaType.ALL));
		HttpSessionRequestCache cache = new HttpSessionRequestCache();
		cache.setRequestMatcher(new AndRequestMatcher(paths.matcher(HttpMethod.GET, "/**"),
				new NegatedRequestMatcher(new OrRequestMatcher(paths.matcher("/favicon.*"),
						paths.matcher("/.well-known/**"), new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest"),
						new RequestHeaderRequestMatcher("HX-Request"), nonPages))));
		return cache;
	}

	/** Without REMEMBER_ME_KEY the key is random, so remember-me cookies stop working after a restart. */
	private static String randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
