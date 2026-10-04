package com.zaika.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * Failed sign-in: back to the login page with the reason ({@code ?error} or {@code ?disabled}) and the typed email
 * kept as a flash attribute, so the visitor only retypes the password.
 */
class LoginFailureHandler implements AuthenticationFailureHandler {

	private final SessionFlashMapManager flashMapManager = new SessionFlashMapManager();

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String email = request.getParameter("email");
		if (StringUtils.hasText(email)) {
			FlashMap flash = new FlashMap();
			flash.put("email", StringUtils.truncate(email.strip(), 254));
			flash.setTargetRequestPath(request.getContextPath() + "/login");
			flashMapManager.saveOutputFlashMap(flash, request, response);
		}
		String reason = exception instanceof DisabledException ? "disabled" : "error";
		response.sendRedirect(request.getContextPath() + "/login?" + reason);
	}

}
