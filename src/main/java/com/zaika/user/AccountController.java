package com.zaika.user;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.security.CurrentUser;

/** /account: profile details and password change, two forms on one page (each re-renders it with its errors). */
@Controller
@RequestMapping("/account")
class AccountController {

	static final String VIEW = "account/profile";

	private final UserService userService;

	AccountController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping
	String profile(@AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("profile", userService.profileForm(me.getId()));
		model.addAttribute("passwordForm", new PasswordChangeForm());
		return VIEW;
	}

	@PostMapping("/profile")
	String updateProfile(@AuthenticationPrincipal CurrentUser me, @Valid @ModelAttribute("profile") ProfileForm form,
			BindingResult result, Model model, RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("passwordForm", new PasswordChangeForm());
			return VIEW;
		}
		userService.updateProfile(me.getId(), form);
		redirect.addFlashAttribute("flashSuccess", "Profile saved.");
		return "redirect:/account";
	}

	@PostMapping("/password")
	String changePassword(@AuthenticationPrincipal CurrentUser me,
			@Valid @ModelAttribute("passwordForm") PasswordChangeForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		form.checkPasswords(result);
		if (!result.hasErrors()) {
			try {
				userService.changePassword(me.getId(), form);
				redirect.addFlashAttribute("flashSuccess", "Password changed. Use the new one next time you sign in.");
				return "redirect:/account";
			}
			catch (WrongPasswordException ex) {
				result.rejectValue("currentPassword", "wrong", "That isn't your current password.");
			}
		}
		model.addAttribute("profile", userService.profileForm(me.getId()));
		return VIEW;
	}

}
