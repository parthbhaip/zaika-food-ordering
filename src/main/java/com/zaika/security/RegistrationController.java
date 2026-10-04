package com.zaika.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.user.EmailTakenException;
import com.zaika.user.RegistrationForm;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserService;

/**
 * Customer sign-up. On success the new customer is signed in straight away (same landing rules as the login form)
 * and greeted with a welcome toast.
 */
@Controller
class RegistrationController {

	private final UserService userService;

	private final ZaikaUserDetailsService userDetails;

	private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

	RegistrationController(UserService userService, ZaikaUserDetailsService userDetails) {
		this.userService = userService;
		this.userDetails = userDetails;
	}

	@GetMapping("/register")
	String form(Authentication authentication, Model model) {
		if (signedIn(authentication)) {
			return "redirect:" + LoginSuccessHandler.landingPage(LoginSuccessHandler.roleOf(authentication));
		}
		model.addAttribute("form", new RegistrationForm());
		return "auth/register";
	}

	@PostMapping("/register")
	String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result,
			Authentication authentication, HttpServletRequest request, HttpServletResponse response,
			RedirectAttributes redirect) {
		if (signedIn(authentication)) {
			return "redirect:" + LoginSuccessHandler.landingPage(LoginSuccessHandler.roleOf(authentication));
		}
		form.checkPasswords(result);
		if (result.hasErrors()) {
			return "auth/register";
		}
		User user;
		try {
			user = userService.registerCustomer(form);
		}
		catch (EmailTakenException ex) {
			result.rejectValue("email", "taken", "This email already has a Zaika account. Sign in instead.");
			return "auth/register";
		}
		String target = signIn(user, request, response);
		redirect.addFlashAttribute("flashSuccess",
				"Welcome to Zaika, " + user.getName().split("\\s+")[0] + "! Your account is ready.");
		return "redirect:" + target;
	}

	private static boolean signedIn(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated();
	}

	/**
	 * Signs the new account in as the login form would: new session id (no fixation), fresh CSRF token, context
	 * saved in the session, then the page that sent the visitor here or the landing page.
	 */
	private String signIn(User user, HttpServletRequest request, HttpServletResponse response) {
		UserDetails details = userDetails.loadUserByUsername(user.getEmail());
		request.getSession();
		request.changeSessionId();
		new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		return new LoginSuccessHandler().targetUrl(request, response, Role.CUSTOMER);
	}

}
