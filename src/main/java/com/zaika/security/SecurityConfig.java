package com.zaika.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

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

	static final String[] PUBLIC_PAGES = { "/", "/about", "/contact", "/terms", "/privacy", "/refund-policy", "/error",
			"/login", "/register", "/forgot-password", "/reset-password", "/restaurants/**", "/r/**", "/search/**",
			"/cart/**", "/partner-with-us/**" };

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

	@Bean
	@Order(2)
	SecurityFilterChain webSecurity(HttpSecurity http, ZaikaUserDetailsService users, PasswordEncoder passwordEncoder,
			UserService userService, Clock clock, Environment env,
			@Value("${app.remember-me-key:}") String rememberMeKey) throws Exception {
		boolean dev = env.acceptsProfiles(Profiles.of("dev"));
		String key = rememberMeKey.isBlank() ? randomKey() : rememberMeKey;

		// A sign-in lock (too many wrong passwords) rejects even the right password, before it is checked, so
		// attempts during the lock are not counted. Other account status (disabled) is checked only after the
		// password matched, so a wrong password never reveals whether an account exists or is switched off.
		// The right password then wipes the failure count (LoginFailureHandler does the counting).
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

		// set explicitly, so it must also accept the tokens the remember-me filter hands it
		http.authenticationManager(new ProviderManager(provider, new RememberMeAuthenticationProvider(key)))
			.authorizeHttpRequests(auth -> {
				// error pages render for everyone, so anonymous 404s and crashes show the branded page
				auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
					.requestMatchers(STATIC_ASSETS).permitAll()
					.requestMatchers(PUBLIC_PAGES).permitAll();
				if (dev) {
					auth.requestMatchers("/dev/**").permitAll();
				}
				AREAS.forEach((prefix, role) -> auth.requestMatchers(prefix + "/**").hasRole(role.name()));
				auth.anyRequest().authenticated();
			})
			.formLogin(form -> form
				.loginPage("/login")
				.usernameParameter("email")
				.successHandler(new LoginSuccessHandler())
				.failureHandler(new LoginFailureHandler(userService)))
			.rememberMe(remember -> remember
				.key(key)
				.userDetailsService(users)
				.tokenValiditySeconds((int) REMEMBER_ME_VALIDITY.toSeconds()))
			.logout(logout -> logout
				.logoutSuccessUrl("/login?logout")
				.deleteCookies("JSESSIONID"))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
		return http.build();
	}

	/** Without REMEMBER_ME_KEY the key is random, so remember-me cookies stop working after a restart. */
	private static String randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
