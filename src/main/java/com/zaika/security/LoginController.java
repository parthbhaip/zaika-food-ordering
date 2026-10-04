package com.zaika.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The sign-in page. Spring Security handles the POST; a visitor who is already signed in goes to their landing page. */
@Controller
class LoginController {

	@GetMapping("/login")
	String login(Authentication authentication) {
		if (authentication != null && authentication.isAuthenticated()) {
			return "redirect:" + LoginSuccessHandler.landingPage(LoginSuccessHandler.roleOf(authentication));
		}
		return "auth/login";
	}

}
