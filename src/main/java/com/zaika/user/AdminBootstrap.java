package com.zaika.user;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * On startup, creates the first admin from {@code app.admin.email} / {@code app.admin.password} (env ADMIN_EMAIL /
 * ADMIN_PASSWORD; the dev profile defaults to the demo admin) when no admin account exists yet. Once there is an
 * admin, the settings are ignored, so changing them never resets a live password. A setting that cannot work
 * (malformed email, weak password, email already used by another role) stops startup with a clear message rather than
 * leaving the platform without an admin silently.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	static final String NAME = "Zaika Admin";

	/** The support line from the contact page; the entity needs a mobile number and admins have no profile page. */
	static final String PHONE = "9876543210";

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final Validator validator;

	private final String email;

	private final String password;

	AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, Validator validator,
			@Value("${app.admin.email:}") String email, @Value("${app.admin.password:}") String password) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.validator = validator;
		this.email = email;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (users.existsByRole(Role.ADMIN)) {
			return;
		}
		if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
			log.warn("No admin account exists. Set ADMIN_EMAIL and ADMIN_PASSWORD to create one on the next start.");
			return;
		}
		// the entity's own rules for email: format and at most 254 characters
		if (!validator.validateValue(User.class, "email", User.normalizeEmail(email)).isEmpty()) {
			throw new IllegalStateException("ADMIN_EMAIL is not a valid email address. Use a full address of at most "
					+ "254 characters, like admin@example.com.");
		}
		if (users.existsByEmailIgnoreCase(email)) {
			throw new IllegalStateException("ADMIN_EMAIL " + User.normalizeEmail(email)
					+ " already belongs to a non-admin account. Choose another address.");
		}
		if (!password.matches(RegistrationForm.PASSWORD_PATTERN)
				|| password.getBytes(StandardCharsets.UTF_8).length > RegistrationForm.PASSWORD_MAX_BYTES) {
			throw new IllegalStateException(
					"ADMIN_PASSWORD is too weak. " + RegistrationForm.PASSWORD_RULE + " Keep it within 72 bytes of UTF-8 (fewer characters if it has accents or emoji).");
		}
		User admin = users.save(new User(NAME, email, PHONE, passwordEncoder.encode(password), Role.ADMIN));
		log.info("Created the admin account {}", admin.getEmail());
	}

}
