package com.zaika.user;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * /forgot-password asks for an email and always answers "check your inbox" (flash attribute {@code sentTo}), whether
 * or not the account exists. /reset-password?token=... shows the new-password form, or an "expired link" state.
 */
@Controller
class PasswordResetController {

	static final String FORGOT_VIEW = "auth/forgot-password";

	static final String RESET_VIEW = "auth/reset-password";

	private final PasswordResetService resets;

	PasswordResetController(PasswordResetService resets) {
		this.resets = resets;
	}

	@GetMapping("/forgot-password")
	String forgotForm(Model model) {
		model.addAttribute("form", new ForgotPasswordForm());
		return FORGOT_VIEW;
	}

	@PostMapping("/forgot-password")
	String sendLink(@Valid @ModelAttribute("form") ForgotPasswordForm form, BindingResult result,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			return FORGOT_VIEW;
		}
		resets.requestReset(form.getEmail());
		redirect.addFlashAttribute("sentTo", form.getEmail());
		return "redirect:/forgot-password";
	}

	@GetMapping("/reset-password")
	String resetForm(@RequestParam(required = false) String token, Model model) {
		if (!resets.isUsable(token)) {
			return linkExpired(model);
		}
		PasswordResetForm form = new PasswordResetForm();
		form.setToken(token);
		model.addAttribute("form", form);
		return RESET_VIEW;
	}

	@PostMapping("/reset-password")
	String reset(@Valid @ModelAttribute("form") PasswordResetForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		if (!resets.isUsable(form.getToken())) {
			return linkExpired(model);
		}
		form.checkPasswords(result);
		if (result.hasErrors()) {
			return RESET_VIEW;
		}
		String email;
		try {
			email = resets.resetPassword(form.getToken(), form.getPassword());
		}
		catch (InvalidResetLinkException ex) {
			return linkExpired(model);
		}
		redirect.addFlashAttribute("email", email);
		redirect.addFlashAttribute("flashSuccess", "Password updated. Sign in with your new password.");
		return "redirect:/login";
	}

	private static String linkExpired(Model model) {
		model.addAttribute("linkExpired", true);
		return RESET_VIEW;
	}

}
