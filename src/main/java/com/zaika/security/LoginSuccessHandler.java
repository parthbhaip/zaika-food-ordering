package com.zaika.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.util.UriComponentsBuilder;

import com.zaika.user.Role;

/**
 * After sign-in: back to the page that asked for it (unless it belongs to another role's area), otherwise to the
 * role's landing page.
 */
class LoginSuccessHandler implements AuthenticationSuccessHandler {

	private final RequestCache requestCache = new HttpSessionRequestCache();

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	static String landingPage(Role role) {
		return switch (role) {
			case CUSTOMER -> "/";
			case RESTAURANT_OWNER -> "/owner";
			case DELIVERY_PARTNER -> "/partner";
			case ADMIN -> "/admin";
		};
	}

	static Role roleOf(Authentication authentication) {
		return authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.filter(authority -> authority.startsWith("ROLE_"))
			.map(authority -> Role.valueOf(authority.substring(5)))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Signed-in user has no role"));
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		String target = targetUrl(request, response, roleOf(authentication));
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		}
		redirectStrategy.sendRedirect(request, response, target);
	}

	/** Where a freshly signed-in user goes; consumes the saved request. Shared with sign-up's auto-login. */
	String targetUrl(HttpServletRequest request, HttpServletResponse response, Role role) {
		String target = landingPage(role);
		SavedRequest saved = requestCache.getRequest(request, response);
		if (saved != null) {
			requestCache.removeRequest(request, response);
			String path = UriComponentsBuilder.fromUriString(saved.getRedirectUrl()).build().getPath();
			Role area = path == null ? null : SecurityConfig.areaOf(path.substring(request.getContextPath().length()));
			if (area == null || area == role) {
				target = saved.getRedirectUrl();
			}
		}
		return target;
	}

}
