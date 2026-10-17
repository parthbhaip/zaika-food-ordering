package com.zaika.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.user.User;

/**
 * Signs a just-created account in, as the login form would, for every sign-up form (customers, restaurant owners,
 * riders): new session id (no fixation, session attributes kept), fresh CSRF token, context saved in the session,
 * session registered in {@link LiveSessions}, guest cart merged (customers) or dropped (staff).
 */
@Component
public class NewAccountSignIn {

	private final ZaikaUserDetailsService userDetails;

	private final LoginSuccessHandler afterSignIn;

	private final SessionRegistry sessions;

	private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

	NewAccountSignIn(ZaikaUserDetailsService userDetails, LoginSuccessHandler afterSignIn, SessionRegistry sessions) {
		this.userDetails = userDetails;
		this.afterSignIn = afterSignIn;
		this.sessions = sessions;
	}

	/** True when the request already carries a signed-in user. */
	public static boolean signedIn(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated();
	}

	/** The landing page of a signed-in user's role ({@code /}, {@code /owner}, {@code /partner}, {@code /admin}). */
	public static String landingPage(Authentication authentication) {
		return LoginSuccessHandler.landingPage(LoginSuccessHandler.roleOf(authentication));
	}

	/**
	 * Signs {@code user} in and returns where to send them next (the page that asked for sign-in, else the role's
	 * landing page). A cart notice from merging a guest cart goes into the flash as {@code flashInfo}.
	 */
	public String signIn(User user, HttpServletRequest request, HttpServletResponse response,
			RedirectAttributes redirect) {
		UserDetails details = userDetails.loadUserByUsername(user.getEmail());
		request.getSession();
		String sessionId = request.changeSessionId();
		sessions.registerNewSession(sessionId, details);
		new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(details, null,
				details.getAuthorities());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		String cartNotice = afterSignIn.mergeGuestCart(request, authentication);
		if (cartNotice != null) {
			redirect.addFlashAttribute("flashInfo", cartNotice);
		}
		return afterSignIn.targetUrl(request, response, user.getRole());
	}

}
