package com.zaika.user;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

	/**
	 * Case-insensitive lookup: emails are stored lower-case, so the argument is normalized the same way and the
	 * unique index on {@code users.email} still serves the query (no {@code UPPER(email)} scan).
	 */
	default Optional<User> findByEmailIgnoreCase(String email) {
		return email == null ? Optional.empty() : findByEmail(User.normalizeEmail(email));
	}

	/** Same, as an existence check (duplicate email on sign-up). */
	default boolean existsByEmailIgnoreCase(String email) {
		return email != null && existsByEmail(User.normalizeEmail(email));
	}

	/** Exact match on the stored (already lower-case) value. Prefer {@link #findByEmailIgnoreCase}. */
	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Loads the user with a row lock, to serialize per-user limits (e.g. the 10-address cap). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findForUpdate(long id);

}
