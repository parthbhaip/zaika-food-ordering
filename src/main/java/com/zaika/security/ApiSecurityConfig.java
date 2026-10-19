package com.zaika.security;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import com.zaika.config.AppProperties;
import com.zaika.user.Role;

import tools.jackson.databind.json.JsonMapper;

/**
 * Security for the mobile app's REST API under {@code /api/**}: its own filter chain, checked before the website's.
 * Stateless (no session, no cookies, so no CSRF), signed in with an RS256 JWT bearer token from
 * {@link ApiAuthService}. Errors are RFC 9457 problem details, never a redirect to the login page.
 * <p>
 * The signing key comes from {@code app.jwt.private-key} (env {@code JWT_PRIVATE_KEY} in the mysql profile): an RSA
 * private key of 2048+ bits in PKCS#8, as PEM or bare base64. When it is not set (dev, test) a key is made at
 * startup, so tokens stop working after a restart.
 */
@Configuration(proxyBeanMethods = false)
class ApiSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfig.class);

	static final String API_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

	/** Sign-up and sign-in need no token. */
	static final String[] PUBLIC_ENDPOINTS = { "/api/v1/auth/register", "/api/v1/auth/login" };

	/** The public catalog: read-only, no token needed (a token that is sent must still be valid). */
	static final String[] PUBLIC_READS = { "/api/v1/cuisines", "/api/v1/restaurants", "/api/v1/restaurants/*",
			"/api/v1/search" };

	/** Role-only API areas: URL prefix and the one role allowed in (everything else needs any signed-in account). */
	static final Map<String, Role> AREAS = Map.of(
			"/api/v1/owner", Role.RESTAURANT_OWNER,
			"/api/v1/rider", Role.DELIVERY_PARTNER);

	static final int MIN_KEY_BITS = 2048;

	@Bean
	KeyPair apiSigningKeys(@Value("${app.jwt.private-key:}") String privateKey) {
		if (!StringUtils.hasText(privateKey)) {
			log.info("app.jwt.private-key (JWT_PRIVATE_KEY) is not set: API tokens are signed with a key made at startup "
					+ "and stop working after a restart");
			return generateKeys();
		}
		return parseKeys(privateKey);
	}

	@Bean
	JwtEncoder apiJwtEncoder(KeyPair apiSigningKeys) {
		return NimbusJwtEncoder.withKeyPair((RSAPublicKey) apiSigningKeys.getPublic(),
				(RSAPrivateCrtKey) apiSigningKeys.getPrivate()).build();
	}

	/** Checks the RS256 signature, the issuer and the expiry, the last against the app's {@link Clock}. */
	@Bean
	JwtDecoder apiJwtDecoder(KeyPair apiSigningKeys, Clock clock, AppProperties app) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) apiSigningKeys.getPublic()).build();
		JwtTimestampValidator expiry = new JwtTimestampValidator();
		expiry.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(expiry, new JwtIssuerValidator(app.baseUrl())));
		return decoder;
	}

	@Bean
	@Order(0)
	SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder apiJwtDecoder, ApiAuthService auth,
			JsonMapper json) throws Exception {
		BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
		http.securityMatcher("/api/**")
			.authorizeHttpRequests(authz -> {
				authz.requestMatchers(PUBLIC_ENDPOINTS).permitAll();
				authz.requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll();
				AREAS.forEach((prefix, role) -> authz.requestMatchers(prefix + "/**").hasRole(role.name()));
				authz.anyRequest().authenticated();
			})
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable())
			.requestCache(cache -> cache.disable())
			.oauth2ResourceServer(server -> server
				.jwt(jwt -> jwt.decoder(apiJwtDecoder).jwtAuthenticationConverter(auth::authenticate))
				.authenticationEntryPoint((request, response, failure) -> {
					bearerEntryPoint.commence(request, response, failure); // status + WWW-Authenticate
					writeProblem(json, request, response, HttpStatus.UNAUTHORIZED,
							"Sign in to use this. Send your access token as \"Authorization: Bearer <token>\".");
				})
				.accessDeniedHandler((request, response, denied) -> {
					// RFC 6750 challenge; Spring's BearerTokenAccessDeniedHandler adds the error only for its own token types
					response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", "
							+ "error_description=\"The request requires higher privileges than provided by the access token.\"");
					writeProblem(json, request, response, HttpStatus.FORBIDDEN, "Your account can't use this.");
				}))
			.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, failure) -> {
				bearerEntryPoint.commence(request, response, failure);
				writeProblem(json, request, response, HttpStatus.UNAUTHORIZED,
						"Sign in to use this. Send your access token as \"Authorization: Bearer <token>\".");
			}))
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(API_CONTENT_SECURITY_POLICY)));
		return http.build();
	}

	/**
	 * An RFC 9457 problem detail written straight to the response (security errors happen before Spring MVC). A
	 * rejected token says why in the detail, everything else uses {@code defaultDetail}.
	 */
	private static void writeProblem(JsonMapper json, HttpServletRequest request, HttpServletResponse response,
			HttpStatus status, String defaultDetail) throws IOException {
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", "about:blank");
		problem.put("title", status.getReasonPhrase());
		problem.put("status", status.value());
		problem.put("detail", invalidToken(response) ? "Your access token is not valid any more. Sign in again." : defaultDetail);
		problem.put("instance", URI.create(request.getRequestURI()).toString());
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		json.writeValue(response.getOutputStream(), problem);
	}

	private static boolean invalidToken(HttpServletResponse response) {
		String challenge = response.getHeader("WWW-Authenticate");
		return challenge != null && challenge.contains("invalid_token");
	}

	static KeyPair generateKeys() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(MIN_KEY_BITS);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("No RSA key generator", ex);
		}
	}

	/** The key pair of a PKCS#8 RSA private key (PEM or bare base64); the public half is derived from it. */
	static KeyPair parseKeys(String privateKey) {
		String base64 = privateKey.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
		RSAPrivateCrtKey key;
		RSAPublicKey publicKey;
		try {
			KeyFactory rsa = KeyFactory.getInstance("RSA");
			key = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
			publicKey = (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
		}
		catch (GeneralSecurityException | IllegalArgumentException | ClassCastException ex) {
			// never put the key itself in the message
			throw new IllegalStateException("JWT_PRIVATE_KEY is not an RSA private key in PKCS#8 (PEM or base64). "
					+ "Make one with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048");
		}
		if (key.getModulus().bitLength() < MIN_KEY_BITS) {
			throw new IllegalStateException("JWT_PRIVATE_KEY must be an RSA key of at least " + MIN_KEY_BITS + " bits");
		}
		return new KeyPair(publicKey, key);
	}

}
