package com.zaika.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Web security. For now: public static pages and assets, security headers (CSP from CLAUDE.md) on every
 * response, everything else behind Spring Security's default login. T07 adds the role rules, the custom login
 * page and the dev-only exceptions ({@code /dev/**}, {@code /h2-console/**}).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; img-src 'self' data:; "
			+ "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

	static final String[] PUBLIC_PAGES = { "/", "/about", "/contact", "/terms", "/privacy", "/refund-policy", "/error" };

	static final String[] STATIC_ASSETS = { "/css/**", "/js/**", "/img/**", "/fonts/**", "/webjars/**", "/favicon.ico" };

	@Bean
	SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(auth -> auth
				.requestMatchers(PUBLIC_PAGES).permitAll()
				.requestMatchers(STATIC_ASSETS).permitAll()
				.anyRequest().authenticated())
			.formLogin(Customizer.withDefaults())
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
		return http.build();
	}

}
