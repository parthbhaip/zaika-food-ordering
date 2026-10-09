package com.zaika.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.user.EmailTakenException;
import com.zaika.user.RegistrationForm;
import com.zaika.user.User;
import com.zaika.user.UserService;

/**
 * Customer sign-up. On success the new customer is signed in straight away (same landing rules as the login form)
 * and greeted with a welcome toast.
 */
@Controller
class RegistrationController {

	private final UserService userService;

	private final NewAccountSignIn newAccountSignIn;

	RegistrationController(UserService userService, NewAccountSignIn newAccountSignIn) {
		this.userService = userService;
		this.newAccountSignIn = newAccountSignIn;
	}

	@GetMapping("/register")
	String form(Authentication authentication, Model model) {
		if (NewAccountSignIn.signedIn(authentication)) {
			return "redirect:" + NewAccountSignIn.landingPage(authentication);
		}
		model.addAttribute("form", new RegistrationForm());
		return "auth/register";
	}

	@PostMapping("/register")
	String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result,
			Authentication authentication, HttpServletRequest request, HttpServletResponse response,
			RedirectAttributes redirect) {
		if (NewAccountSignIn.signedIn(authentication)) {
			return "redirect:" + NewAccountSignIn.landingPage(authentication);
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
		redirect.addFlashAttribute("flashSuccess",
				"Welcome to Zaika, " + user.firstName() + "! Your account is ready.");
		return "redirect:" + newAccountSignIn.signIn(user, request, response, redirect);
	}

}
