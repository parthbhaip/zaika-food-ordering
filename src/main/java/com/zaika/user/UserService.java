package com.zaika.user;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;

/** Account rules: sign-up, profile edits, password changes and the lock after repeated wrong passwords. */
@Service
public class UserService {

	/** Wrong passwords in a row that lock sign-in. */
	public static final int MAX_FAILED_LOGINS = 5;

	/** How long sign-in stays locked after {@link #MAX_FAILED_LOGINS} wrong passwords. */
	public static final Duration LOGIN_LOCK = Duration.ofMinutes(15);

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	UserService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/**
	 * Creates a CUSTOMER account from a validated form. Not one transaction on purpose: the unique index is the real
	 * guard, and a lost race (double-click on "Create account") must surface as {@link EmailTakenException}, which a
	 * rollback-only outer transaction would turn into an error page.
	 *
	 * @throws EmailTakenException if the email (in any letter case) already has an account
	 */
	public User registerCustomer(RegistrationForm form) {
		if (users.existsByEmailIgnoreCase(form.getEmail())) {
			throw new EmailTakenException();
		}
		User user = new User(form.getName().strip(), form.getEmail(), form.getPhone(),
				passwordEncoder.encode(form.getPassword()), Role.CUSTOMER);
		try {
			return users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			if (isEmailTaken(ex)) {
				throw new EmailTakenException();
			}
			throw ex;
		}
	}

	/** H2 and MySQL both name the violated index in the message ("UK_USERS_EMAIL_INDEX_4", "users.uk_users_email"). */
	private static boolean isEmailTaken(DataIntegrityViolationException ex) {
		String message = ex.getMostSpecificCause().getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("uk_users_email");
	}

	/** @throws NotFoundException if there is no such account */
	@Transactional(readOnly = true)
	public User get(long userId) {
		return users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId));
	}

	@Transactional(readOnly = true)
	public ProfileForm profileForm(long userId) {
		return ProfileForm.of(get(userId));
	}

	/** Saves the name (trimmed) and the normalized mobile number. */
	@Transactional
	public void updateProfile(long userId, ProfileForm form) {
		get(userId).updateProfile(form.getName().strip(), form.getPhone());
	}

	/**
	 * Sets a new password after checking the current one. Remember-me cookies are signed with the old hash, so they
	 * stop working everywhere.
	 *
	 * @throws WrongPasswordException if the current password is wrong
	 */
	@Transactional
	public void changePassword(long userId, PasswordChangeForm form) {
		User user = get(userId);
		if (!passwordEncoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
			throw new WrongPasswordException();
		}
		user.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
	}

	/**
	 * Counts a wrong password for the account with this email (any letter case). Unknown emails are ignored, so they
	 * never show a lock.
	 *
	 * @return when sign-in unlocks, if the account is locked now (by this attempt or an earlier one)
	 */
	@Transactional
	public Optional<LocalDateTime> recordFailedLogin(String email) {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<User> user = users.findForUpdateByEmail(User.normalizeEmail(email));
		user.ifPresent(u -> u.recordFailedLogin(now, MAX_FAILED_LOGINS, LOGIN_LOCK));
		return user.filter(u -> u.isLockedAt(now)).map(User::getLockedUntil);
	}

	/** Called after a correct password: the failure count starts again from zero. Writes only if there was one. */
	@Transactional
	public void recordSuccessfulLogin(long userId) {
		users.findById(userId).ifPresent(User::clearFailedLogins);
	}

}
