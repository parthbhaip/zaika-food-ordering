package com.zaika.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;

/** Account rules: sign-up, profile edits and password changes. */
@Service
public class UserService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	UserService(UserRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
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
			throw new EmailTakenException();
		}
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

}
