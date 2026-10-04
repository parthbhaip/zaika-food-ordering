package com.zaika.security;

import java.time.LocalDateTime;

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

	/**
	 * End of a lock after too many wrong passwords, or {@code null}. Deliberately not Spring's
	 * {@code accountNonLocked}: the lock stops password sign-in only (see {@link SecurityConfig}), so someone guessing
	 * passwords cannot also void the owner's remember-me cookie.
	 */
	private final LocalDateTime lockedUntil;

	CurrentUser(com.zaika.user.User user) {
		this(user.getId(), user.getEmail(), user.getPasswordHash(), user.isEnabled(), user.getRole(),
				user.getLockedUntil());
	}

	public CurrentUser(long id, String email, String passwordHash, boolean enabled, Role role) {
		this(id, email, passwordHash, enabled, role, null);
	}

	private CurrentUser(long id, String email, String passwordHash, boolean enabled, Role role,
			LocalDateTime lockedUntil) {
		super(email, passwordHash, enabled, true, true, true, AuthorityUtils.createAuthorityList("ROLE_" + role.name()));
		this.id = id;
		this.lockedUntil = lockedUntil;
	}

	public long getId() {
		return id;
	}

	/** True while password sign-in is locked after too many wrong passwords. */
	public boolean isSignInLockedAt(LocalDateTime now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	// equals/hashCode stay Spring's (by username): one account, one principal

}
