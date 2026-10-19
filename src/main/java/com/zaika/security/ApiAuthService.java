package com.zaika.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;

import com.zaika.config.AppProperties;
import com.zaika.user.RegistrationForm;
import com.zaika.user.User;
import com.zaika.user.UserRepository;
import com.zaika.user.UserService;

/**
 * Sign-up and sign-in for the REST API, and the access tokens they hand out: RS256 JWTs (keys in
 * {@link ApiSecurityConfig}) with the account id as subject, valid for {@link #TOKEN_LIFETIME}. Every API request
 * re-reads the account ({@link #authenticate}), so a token stops working as soon as the account is switched off or
 * its password changes (the token carries a fingerprint of the password hash).
 */
@Service
public class ApiAuthService {

	public static final Duration TOKEN_LIFETIME = Duration.ofDays(7);

	static final String PASSWORD_STAMP_CLAIM = "pwd";

	private final AuthenticationManager authenticationManager;

	private final UserService userService;

	private final UserRepository users;

	private final JwtEncoder encoder;

	private final Clock clock;

	private final String issuer;

	ApiAuthService(AuthenticationManager authenticationManager, UserService userService, UserRepository users,
			JwtEncoder apiJwtEncoder, Clock clock, AppProperties app) {
		this.authenticationManager = authenticationManager;
		this.userService = userService;
		this.users = users;
		this.encoder = apiJwtEncoder;
		this.clock = clock;
		this.issuer = app.baseUrl();
	}

	/** An access token and the account it belongs to. */
	public record SignedIn(String accessToken, Instant expiresAt, User user) {
	}

	/**
	 * Creates a CUSTOMER account from a validated form and signs it in.
	 *
	 * @throws com.zaika.user.EmailTakenException if the email already has an account
	 */
	public SignedIn register(RegistrationForm form) {
		return issue(userService.registerCustomer(form));
	}

	/**
	 * Checks the email and password with the website's own rules: five wrong passwords lock password sign-in for 15
	 * minutes, and a switched-off account is reported only after the right password.
	 *
	 * @throws ApiSignInException with the reason when sign-in is refused
	 */
	public SignedIn login(String email, String password) {
		Authentication result;
		try {
			result = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
		}
		catch (DisabledException ex) {
			throw new ApiSignInException(ApiSignInException.Reason.DISABLED, null);
		}
		catch (BadCredentialsException | LockedException ex) {
			Optional<LocalDateTime> lockedUntil = userService.recordFailedLogin(email);
			throw lockedUntil.map(until -> new ApiSignInException(ApiSignInException.Reason.LOCKED, until))
				.orElseGet(() -> new ApiSignInException(ApiSignInException.Reason.WRONG_PASSWORD, null));
		}
		return issue(userService.get(((CurrentUser) result.getPrincipal()).getId()));
	}

	SignedIn issue(User user) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(TOKEN_LIFETIME);
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject(String.valueOf(user.getId()))
			.issuedAt(now)
			.expiresAt(expiresAt)
			.claim("role", user.getRole().name())
			.claim(PASSWORD_STAMP_CLAIM, passwordStamp(user.getPasswordHash()))
			.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
			.getTokenValue();
		return new SignedIn(token, expiresAt, user);
	}

	/**
	 * Turns a token whose signature, issuer and expiry already checked out into the signed-in account
	 * ({@link CurrentUser}, role from the database). One query per request.
	 *
	 * @throws InvalidBearerTokenException if the account is gone, switched off or has a new password since
	 */
	UsernamePasswordAuthenticationToken authenticate(Jwt jwt) {
		User user = parseId(jwt.getSubject()).flatMap(users::findById)
			.filter(User::isEnabled)
			.filter(u -> passwordStamp(u.getPasswordHash()).equals(jwt.getClaimAsString(PASSWORD_STAMP_CLAIM)))
			.orElseThrow(() -> new InvalidBearerTokenException("The account behind this token can't sign in any more"));
		CurrentUser me = new CurrentUser(user);
		return UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities());
	}

	private static Optional<Long> parseId(String subject) {
		try {
			return Optional.of(Long.parseLong(subject));
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	/** A short fingerprint of the password hash: changes whenever the password does, reveals nothing usable. */
	static String passwordStamp(String passwordHash) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
