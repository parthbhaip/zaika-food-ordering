package com.zaika.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Account rules: sign-up today, profile and password changes later. */
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

}
