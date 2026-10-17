package com.zaika.security;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * The signed-in sessions of each account (every web sign-in registers its session: the login form, remember-me and
 * new accounts). Ending them signs the account out everywhere on its next request, which lands on
 * {@code /login?disabled}.
 */
@Component
public class LiveSessions {

	private final SessionRegistry registry;

	LiveSessions(SessionRegistry registry) {
		this.registry = registry;
	}

	/** Ends every session of the account {@code userId}; the browser is signed out on its next request. */
	public void endAll(long userId) {
		for (Object principal : registry.getAllPrincipals()) {
			if (principal instanceof CurrentUser user && user.getId() == userId) {
				registry.getAllSessions(principal, false).forEach(SessionInformation::expireNow);
			}
		}
	}

}
