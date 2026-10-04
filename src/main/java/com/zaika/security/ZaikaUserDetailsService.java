package com.zaika.security;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.user.User;
import com.zaika.user.UserRepository;

/** Loads accounts for login and remember-me by email (case-insensitive). Returns a {@link CurrentUser}. */
@Service
public class ZaikaUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	ZaikaUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public CurrentUser loadUserByUsername(String email) {
		User user = users.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new UsernameNotFoundException("No account with that email"));
		return new CurrentUser(user);
	}

}
