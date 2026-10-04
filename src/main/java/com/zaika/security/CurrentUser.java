package com.zaika.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

import com.zaika.user.Role;

/**
 * The signed-in account as Spring Security holds it: username = stored email, authority {@code ROLE_<role>}, plus
 * the account id so controllers can pass it to services without another lookup. Read it with
 * {@code @AuthenticationPrincipal CurrentUser me}.
 */
public class CurrentUser extends User {

	private final long id;

	CurrentUser(com.zaika.user.User user) {
		this(user.getId(), user.getEmail(), user.getPasswordHash(), user.isEnabled(), user.getRole());
	}

	public CurrentUser(long id, String email, String passwordHash, boolean enabled, Role role) {
		super(email, passwordHash, enabled, true, true, true, AuthorityUtils.createAuthorityList("ROLE_" + role.name()));
		this.id = id;
	}

	public long getId() {
		return id;
	}

	// equals/hashCode stay Spring's (by username): one account, one principal

}
