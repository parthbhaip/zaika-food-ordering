package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.sql.SQLException;
import java.time.Clock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Sign-up when the existence check passes but the insert still hits a unique index (a double-click race): only the
 * email index means "taken"; any other integrity failure is a real error and must not be dressed up as one.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceRegistrationRaceTest {

	static final String EMAIL = "race@race-test.zaika.test";

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	Clock clock;

	@AfterEach
	void cleanUp() {
		users.findByEmailIgnoreCase(EMAIL).ifPresent(users::delete);
	}

	@Test
	void losingTheRaceOnTheEmailIndexIsEmailTaken() {
		users.save(new User("Asha Patil", EMAIL, "9876543210", passwordEncoder.encode("Kolhapur1"), Role.CUSTOMER));
		UserRepository racing = mock(UserRepository.class, AdditionalAnswers.delegatesTo(users));
		doReturn(false).when(racing).existsByEmailIgnoreCase(anyString()); // the other request had not committed yet

		assertThatThrownBy(() -> new UserService(racing, passwordEncoder, clock).registerCustomer(form()))
			.isInstanceOf(EmailTakenException.class);
	}

	@Test
	void anyOtherIntegrityViolationIsRethrown() {
		UserRepository broken = mock(UserRepository.class, AdditionalAnswers.delegatesTo(users));
		DataIntegrityViolationException other = new DataIntegrityViolationException("insert failed",
				new SQLException("NULL not allowed for column \"PHONE\""));
		doThrow(other).when(broken).saveAndFlush(any(User.class));

		assertThatThrownBy(() -> new UserService(broken, passwordEncoder, clock).registerCustomer(form()))
			.isSameAs(other);
	}

	private static RegistrationForm form() {
		RegistrationForm form = new RegistrationForm();
		form.setName("Asha Patil");
		form.setEmail(EMAIL);
		form.setPhone("9876543210");
		form.setPassword("Kolhapur1");
		form.setConfirmPassword("Kolhapur1");
		form.setAcceptTerms(true);
		return form;
	}

}
