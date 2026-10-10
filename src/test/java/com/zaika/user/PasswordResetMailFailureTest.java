package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.zaika.config.AppProperties;
import com.zaika.notification.MailService;
import com.zaika.support.MutableClock;

/** A mail server outage must not turn "forgot password" into an error page (that would also reveal the account). */
class PasswordResetMailFailureTest {

	private final UserRepository users = mock(UserRepository.class);

	private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);

	private final MailService mail = mock(MailService.class);

	private final AppProperties app = new AppProperties("Zaika", ZoneId.of("Asia/Kolkata"), "./uploads", false,
			BigDecimal.ONE, new BigDecimal("0.05"), BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("0.80"), "https://zaika.example.in");

	private final PasswordResetService resets = new PasswordResetService(users, tokens, mock(PasswordEncoder.class),
			mail, app, new MutableClock(app.zone()));

	@Test
	void mailFailureIsLoggedNotThrown() {
		User user = new User("Asha Patil", "asha@zaika.test", "9876543210", "hash", Role.CUSTOMER);
		when(users.findByEmailIgnoreCase("asha@zaika.test")).thenReturn(Optional.of(user));
		doThrow(new MailSendException("SMTP down")).when(mail).send(anyString(), anyString(), anyString());

		assertThatNoException().isThrownBy(() -> resets.requestReset("asha@zaika.test"));

		verify(tokens).save(any(PasswordResetToken.class));
		verify(mail).send(eq("asha@zaika.test"), eq("Reset your Zaika password"), anyString());
	}

	@Test
	void greetingUsesTheFirstNameWhateverWhitespaceFollowsIt() {
		User user = new User("Asha\tPatil", "asha@zaika.test", "9876543210", "hash", Role.CUSTOMER);
		when(users.findByEmailIgnoreCase("asha@zaika.test")).thenReturn(Optional.of(user));

		resets.requestReset("asha@zaika.test");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mail).send(eq("asha@zaika.test"), anyString(), body.capture());
		assertThat(body.getValue()).startsWith("Hi Asha,");
	}

}
