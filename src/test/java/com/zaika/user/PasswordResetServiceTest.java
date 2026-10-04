package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.support.MutableClock;

/**
 * Forgot / reset password rules: hashed single-use tokens, 30-minute expiry, the same (silent) outcome for unknown
 * emails, and the reset link written to the log by LoggingMailService in the test profile.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetServiceTest {

	static final String EMAIL = "asha.patil@reset-test.zaika.test";

	static final String OLD_PASSWORD = "Kolhapur1";

	static final String NEW_PASSWORD = "Tambda2Rassa";

	private static final Pattern LINK = Pattern.compile("http://localhost:8080/reset-password\\?token=([A-Za-z0-9_-]+)");

	@Autowired
	PasswordResetService resets;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordResetTokenRepository tokens;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	User user;

	@BeforeEach
	void createAccount() {
		user = users.save(new User("Asha Patil", EMAIL, "9876543210", passwordEncoder.encode(OLD_PASSWORD), Role.CUSTOMER));
	}

	@AfterEach
	void cleanUp() {
		clock.reset();
		deleteTestAccounts(users);
	}

	static void deleteTestAccounts(UserRepository users) {
		users.findAll().stream().filter(u -> u.getEmail().endsWith("@reset-test.zaika.test")).forEach(users::delete);
	}

	/** The token from the newest reset link in the log. */
	static String lastToken(CapturedOutput output) {
		Matcher matcher = LINK.matcher(output.getAll());
		String token = null;
		while (matcher.find()) {
			token = matcher.group(1);
		}
		assertThat(token).as("a reset link was logged").isNotNull();
		return token;
	}

	@Test
	void linkIsMailedToTheAccountAndOnlyItsHashIsStored(CapturedOutput output) {
		resets.requestReset("  Asha.Patil@Reset-Test.zaika.test ");

		String token = lastToken(output);
		assertThat(output).contains("to=" + EMAIL + " subject=\"Reset your Zaika password\"")
			.contains("Hi Asha,")
			.contains("The link works once and expires in 30 minutes.");
		assertThat(token).hasSize(43); // 32 random bytes, base64url without padding

		List<PasswordResetToken> stored = tokensOf(user);
		assertThat(stored).singleElement().satisfies(t -> {
			assertThat(t.getTokenHash()).isEqualTo(PasswordResetService.hash(token)).hasSize(64).isNotEqualTo(token);
			assertThat(t.getExpiresAt()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(30));
			assertThat(t.getUsedAt()).isNull();
		});
		assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where token_hash = ?", Long.class, token))
			.as("the raw token is never stored").isZero();
		assertThat(resets.isUsable(token)).isTrue();
	}

	@Test
	void everyRequestMakesADifferentLink(CapturedOutput output) {
		resets.requestReset(EMAIL);
		String first = lastToken(output);
		resets.requestReset(EMAIL);
		String second = lastToken(output);

		assertThat(second).isNotEqualTo(first);
		assertThat(resets.isUsable(first)).isTrue();
		assertThat(resets.isUsable(second)).isTrue();
	}

	@Test
	void unknownEmailSendsAndStoresNothingAndDoesNotFail(CapturedOutput output) {
		long before = tokens.count();

		assertThatNoException().isThrownBy(() -> resets.requestReset("nobody@reset-test.zaika.test"));

		assertThat(tokens.count()).isEqualTo(before);
		assertThat(output).doesNotContain("Reset your Zaika password");
	}

	@Test
	void switchedOffAccountGetsNoLink(CapturedOutput output) {
		user.setEnabled(false);
		users.save(user);

		resets.requestReset(EMAIL);

		assertThat(tokensOf(user)).isEmpty();
		assertThat(output).doesNotContain("Reset your Zaika password");
	}

	@Test
	void resetSetsTheNewPasswordAndTheLinkWorksOnlyOnce(CapturedOutput output) {
		resets.requestReset(EMAIL);
		String token = lastToken(output);

		assertThat(resets.resetPassword(token, NEW_PASSWORD)).isEqualTo(EMAIL);

		String hash = users.findByEmail(EMAIL).orElseThrow().getPasswordHash();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, hash)).isTrue();
		assertThat(passwordEncoder.matches(OLD_PASSWORD, hash)).isFalse();
		assertThat(tokensOf(user)).singleElement()
			.satisfies(t -> assertThat(t.getUsedAt()).isEqualTo(MutableClock.DEFAULT_NOW));
		assertThat(resets.isUsable(token)).isFalse();
		assertThatThrownBy(() -> resets.resetPassword(token, "Another9pass")).isInstanceOf(InvalidResetLinkException.class);
		assertThat(passwordEncoder.matches(NEW_PASSWORD, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
	}

	@Test
	void resettingSpendsTheAccountsOtherOpenLinksToo(CapturedOutput output) {
		resets.requestReset(EMAIL);
		String older = lastToken(output);
		resets.requestReset(EMAIL);
		String newer = lastToken(output);

		resets.resetPassword(newer, NEW_PASSWORD);

		assertThat(resets.isUsable(older)).isFalse();
		assertThatThrownBy(() -> resets.resetPassword(older, OLD_PASSWORD)).isInstanceOf(InvalidResetLinkException.class);
	}

	@Test
	void linkExpiresThirtyMinutesAfterItWasSent(CapturedOutput output) {
		resets.requestReset(EMAIL);
		String token = lastToken(output);

		clock.advance(Duration.ofMinutes(30).minusSeconds(1));
		assertThat(resets.isUsable(token)).isTrue();

		clock.advance(Duration.ofSeconds(1));
		assertThat(resets.isUsable(token)).isFalse();
		assertThatThrownBy(() -> resets.resetPassword(token, NEW_PASSWORD)).isInstanceOf(InvalidResetLinkException.class);
		assertThat(passwordEncoder.matches(OLD_PASSWORD, users.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
	}

	@Test
	void linkStopsWorkingWhenTheAccountIsSwitchedOff(CapturedOutput output) {
		resets.requestReset(EMAIL);
		String token = lastToken(output);
		user.setEnabled(false);
		users.save(user);

		assertThat(resets.isUsable(token)).isFalse();
		assertThatThrownBy(() -> resets.resetPassword(token, NEW_PASSWORD)).isInstanceOf(InvalidResetLinkException.class);
	}

	@Test
	void madeUpMissingOrBlankTokensAreNotUsable() {
		assertThat(resets.isUsable("not-a-real-token")).isFalse();
		assertThat(resets.isUsable(null)).isFalse();
		assertThat(resets.isUsable("  ")).isFalse();
		assertThatThrownBy(() -> resets.resetPassword(null, NEW_PASSWORD)).isInstanceOf(InvalidResetLinkException.class);
	}

	@Test
	void resetLiftsAFailedLoginLock(CapturedOutput output) {
		jdbc.update("update users set failed_login_count = 5, locked_until = ? where id = ?",
				MutableClock.DEFAULT_NOW.plusMinutes(15), user.getId());
		resets.requestReset(EMAIL);

		resets.resetPassword(lastToken(output), NEW_PASSWORD);

		User reloaded = users.findByEmail(EMAIL).orElseThrow();
		assertThat(reloaded.getFailedLoginCount()).isZero();
		assertThat(reloaded.getLockedUntil()).isNull();
	}

	private List<PasswordResetToken> tokensOf(User account) {
		return tokens.findAll().stream().filter(t -> t.getUser().getId().equals(account.getId())).toList();
	}

}
