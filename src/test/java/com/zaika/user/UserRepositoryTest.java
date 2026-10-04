package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.config.TimeConfig;
import com.zaika.support.MutableClock;
import com.zaika.support.TestClockConfig;

/** The users table (Flyway V1) and its repository on H2 in MySQL mode. */
@DataJpaTest
@ActiveProfiles("test")
@Import({ TimeConfig.class, TestClockConfig.class })
class UserRepositoryTest {

	private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuK7bQ6cU8l5n0yVq3uJ4z1fKpJ2m5Wq6";

	@Autowired
	UserRepository users;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void savesAnAccountWithSafeDefaults() {
		User saved = users.saveAndFlush(new User("Asha Patil", "asha@example.com", "9876543210", HASH, Role.CUSTOMER));
		em.clear();

		User loaded = users.findById(saved.getId()).orElseThrow();
		assertThat(loaded.getName()).isEqualTo("Asha Patil");
		assertThat(loaded.getPhone()).isEqualTo("9876543210");
		assertThat(loaded.getPasswordHash()).isEqualTo(HASH);
		assertThat(loaded.getRole()).isEqualTo(Role.CUSTOMER);
		assertThat(loaded.isEnabled()).isTrue();
		assertThat(loaded.getFailedLoginCount()).isZero();
		assertThat(loaded.getLockedUntil()).isNull();
		assertThat(loaded.isLockedAt(MutableClock.DEFAULT_NOW)).isFalse();
		assertThat(loaded.getCreatedAt()).isEqualTo(MutableClock.DEFAULT_NOW);
	}

	@Test
	void emailIsStoredTrimmedAndLowerCase() {
		User saved = users.saveAndFlush(new User("Ravi Jadhav", "  Ravi.Jadhav@Example.COM ", "8123456789", HASH,
				Role.RESTAURANT_OWNER));

		assertThat(saved.getEmail()).isEqualTo("ravi.jadhav@example.com");
		Map<String, Object> row = jdbc.queryForMap("SELECT email, role FROM users WHERE id = ?", saved.getId());
		assertThat(row).containsEntry("email", "ravi.jadhav@example.com").containsEntry("role", "RESTAURANT_OWNER");
	}

	@Test
	void lookupByEmailIgnoresCaseAndSurroundingSpaces() {
		users.saveAndFlush(new User("Meera Kulkarni", "meera@example.com", "7012345678", HASH, Role.DELIVERY_PARTNER));
		em.clear();

		assertThat(users.findByEmailIgnoreCase("MEERA@Example.com")).get()
			.extracting(User::getName)
			.isEqualTo("Meera Kulkarni");
		assertThat(users.findByEmailIgnoreCase(" meera@example.com ")).isPresent();
		assertThat(users.existsByEmailIgnoreCase("Meera@EXAMPLE.com")).isTrue();
		assertThat(users.findByEmailIgnoreCase("someone@example.com")).isEmpty();
		assertThat(users.existsByEmailIgnoreCase("someone@example.com")).isFalse();
		assertThat(users.findByEmailIgnoreCase(null)).isEmpty();
		assertThat(users.existsByEmailIgnoreCase(null)).isFalse();
	}

	@Test
	void emailIsUniqueRegardlessOfCase() {
		users.saveAndFlush(new User("Sanjay More", "sanjay@example.com", "9123456780", HASH, Role.CUSTOMER));

		assertThatThrownBy(
				() -> users.saveAndFlush(new User("Sanjay M", "SANJAY@example.com", "9123456781", HASH, Role.ADMIN)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void phoneMustBeATenDigitIndianMobileNumber() {
		for (String bad : new String[] { "5123456789", "012345678", "98765432101", "+919876543210", "98765 43210" }) {
			assertThatThrownBy(() -> users.saveAndFlush(new User("Test User", "bad@example.com", bad, HASH, Role.CUSTOMER)))
				.as(bad)
				.isInstanceOf(ConstraintViolationException.class);
			em.clear(); // the rejected instance stays in the session without an id
		}
		for (String good : new String[] { "6000000000", "7999999999", "8888888888", "9876543210" }) {
			assertThat(users.saveAndFlush(new User("Test User", good + "@example.com", good, HASH, Role.CUSTOMER))
				.getId()).as(good).isNotNull();
		}
	}

	@Test
	void everyRoleRoundTripsAndUnknownRolesAreRejectedByTheDatabase() {
		int n = 0;
		for (Role role : Role.values()) {
			n++;
			User saved = users.saveAndFlush(new User("User " + n, "role" + n + "@example.com", "900000000" + n, HASH, role));
			em.clear();
			assertThat(users.findById(saved.getId()).orElseThrow().getRole()).isEqualTo(role);
		}

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO users (name, email, phone, password_hash, role, enabled, failed_login_count,
				                   created_at, updated_at, version)
				VALUES ('X', 'x@example.com', '9000000009', 'h', 'SUPERUSER', TRUE, 0, NOW(), NOW(), 0)"""))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidEmailAndBlankNameAreRejected() {
		assertThatThrownBy(() -> users.saveAndFlush(new User("Test", "not-an-email", "9876543210", HASH, Role.CUSTOMER)))
			.isInstanceOf(ConstraintViolationException.class);
		em.clear();
		assertThatThrownBy(() -> users.saveAndFlush(new User(" ", "blank@example.com", "9876543210", HASH, Role.CUSTOMER)))
			.isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void lockIsInForceOnlyUntilLockedUntil() {
		User saved = users.saveAndFlush(new User("Lock Test", "lock@example.com", "9876543210", HASH, Role.CUSTOMER));
		jdbc.update("UPDATE users SET locked_until = ? WHERE id = ?", MutableClock.DEFAULT_NOW.plusMinutes(15),
				saved.getId());
		em.clear();

		User loaded = users.findById(saved.getId()).orElseThrow();
		assertThat(loaded.isLockedAt(MutableClock.DEFAULT_NOW)).isTrue();
		assertThat(loaded.isLockedAt(MutableClock.DEFAULT_NOW.plusMinutes(15))).isFalse();
	}

}
