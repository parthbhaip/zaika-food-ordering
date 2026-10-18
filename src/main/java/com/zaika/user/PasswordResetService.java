package com.zaika.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.config.AppProperties;
import com.zaika.notification.AfterCommit;
import com.zaika.notification.MailService;

/**
 * Forgot / reset password. A link carries a random 256-bit token; only its SHA-256 hash is stored. It works once and
 * for {@link #LINK_LIFETIME}. Asking for a link never reveals whether an account exists: callers show the same
 * message, and nothing here throws for an unknown email.
 */
@Service
public class PasswordResetService {

	public static final Duration LINK_LIFETIME = Duration.ofMinutes(30);

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private final UserRepository users;

	private final PasswordResetTokenRepository tokens;

	private final PasswordEncoder passwordEncoder;

	private final MailService mail;

	private final AppProperties app;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, PasswordEncoder passwordEncoder,
			MailService mail, AppProperties app, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.mail = mail;
		this.app = app;
		this.clock = clock;
	}

	/**
	 * Emails a reset link if an enabled account has this email (any letter case); otherwise does nothing. The mail
	 * goes out once the link is committed; a mail server failure is logged, not thrown, so the visitor sees the same
	 * answer either way.
	 */
	@Transactional
	public void requestReset(String email) {
		users.findByEmailIgnoreCase(email).filter(User::isEnabled).ifPresent(this::sendLink);
	}

	private void sendLink(User user) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		tokens.save(new PasswordResetToken(user, hash(token), now().plus(LINK_LIFETIME)));
		String link = app.baseUrl() + "/reset-password?token=" + token;
		AfterCommit.mail(mail, user.getEmail(), "Reset your " + app.brandName() + " password", message(user, link),
				"Password reset email for user " + user.getId());
	}

	/**
	 * Nightly clean-up: deletes links that expired (used or not) more than a day ago. They can never work again, so
	 * keeping them only grows the table.
	 */
	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
	@Transactional
	public int purgeExpired() {
		int purged = tokens.deleteExpiredBefore(now().minusDays(1));
		if (purged > 0) {
			log.info("Purged {} expired password reset links", purged);
		}
		return purged;
	}

	private String message(User user, String link) {
		return """
				Hi %s,

				We got a request to reset the password of your %s account. Choose a new password here:

				%s

				The link works once and expires in %d minutes. If you didn't ask for it, ignore this email and \
				your password stays the same.

				Team %s
				""".formatted(user.firstName(), app.brandName(), link, LINK_LIFETIME.toMinutes(),
				app.brandName());
	}

	/** True if the link is unused, unexpired and belongs to an enabled account. */
	@Transactional(readOnly = true)
	public boolean isUsable(String token) {
		return lookUp(token, tokens::findByTokenHash).isPresent();
	}

	/**
	 * Sets a new (already validated) password and spends this and every other open link of the account. Also lifts a
	 * failed-login lock. Remember-me cookies stop working because they are signed with the old hash.
	 *
	 * @return the account's email, to pre-fill the sign-in form
	 * @throws InvalidResetLinkException if the link is unknown, used, expired or its account is switched off
	 */
	@Transactional
	public String resetPassword(String token, String newPassword) {
		User user = lookUp(token, tokens::findForUpdateByTokenHash).orElseThrow(InvalidResetLinkException::new).getUser();
		user.resetPassword(passwordEncoder.encode(newPassword));
		tokens.markAllUsed(user.getId(), now());
		return user.getEmail();
	}

	private Optional<PasswordResetToken> lookUp(String token,
			Function<String, Optional<PasswordResetToken>> finder) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		LocalDateTime now = now();
		return finder.apply(hash(token)).filter(t -> t.isUsableAt(now) && t.getUser().isEnabled());
	}

	static String hash(String token) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is part of every JDK", ex);
		}
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

}
