package com.zaika.security;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import com.zaika.user.UserService;

/**
 * Failed sign-in: back to the login page with the reason ({@code ?error}, {@code ?locked} or {@code ?disabled}) and
 * the typed email kept as a flash attribute, so the visitor only retypes the password. A wrong password counts
 * towards the sign-in lock ({@link UserService#recordFailedLogin}); once locked, the unlock time comes along as the
 * flash attribute {@code lockedUntil}.
 */
class LoginFailureHandler implements AuthenticationFailureHandler {

	private final SessionFlashMapManager flashMapManager = new SessionFlashMapManager();

	private final UserService userService;

	LoginFailureHandler(UserService userService) {
		this.userService = userService;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String email = request.getParameter("email");
		FlashMap flash = new FlashMap();
		String reason = "error";
		if (exception instanceof DisabledException) {
			reason = "disabled";
		}
		else if (StringUtils.hasText(email)
				&& (exception instanceof BadCredentialsException || exception instanceof LockedException)) {
			Optional<LocalDateTime> lockedUntil = userService.recordFailedLogin(email);
			if (lockedUntil.isPresent()) {
				reason = "locked";
				flash.put("lockedUntil", lockedUntil.get());
			}
		}
		if (StringUtils.hasText(email)) {
			flash.put("email", StringUtils.truncate(email.strip(), 254));
		}
		if (!flash.isEmpty()) {
			flash.setTargetRequestPath(request.getContextPath() + "/login");
			flashMapManager.saveOutputFlashMap(flash, request, response);
		}
		response.sendRedirect(request.getContextPath() + "/login?" + reason);
	}

}
