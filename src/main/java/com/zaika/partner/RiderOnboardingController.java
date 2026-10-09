package com.zaika.partner;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.RiderOnboardingService;
import com.zaika.delivery.RiderSignupForm;
import com.zaika.delivery.VehicleType;
import com.zaika.security.NewAccountSignIn;
import com.zaika.user.EmailTakenException;
import com.zaika.user.User;

/**
 * "Become a delivery partner" ({@code /partner-with-us/rider}, public): one form creates the rider account and a
 * PENDING profile, then signs the rider in and takes them to the rider panel, which shows the review status.
 * Signed-in visitors are sent back to their own area: riding needs its own partner account.
 */
@Controller
@RequestMapping("/partner-with-us/rider")
class RiderOnboardingController {

	private final RiderOnboardingService onboarding;

	private final NewAccountSignIn newAccountSignIn;

	RiderOnboardingController(RiderOnboardingService onboarding, NewAccountSignIn newAccountSignIn) {
		this.onboarding = onboarding;
		this.newAccountSignIn = newAccountSignIn;
	}

	@GetMapping
	String form(Authentication authentication, Model model, RedirectAttributes redirect) {
		if (NewAccountSignIn.signedIn(authentication)) {
			return alreadySignedIn(authentication, redirect);
		}
		model.addAttribute("form", new RiderSignupForm());
		return page(model);
	}

	@PostMapping
	String register(@Valid @ModelAttribute("form") RiderSignupForm form, BindingResult result,
			Authentication authentication, Model model, HttpServletRequest request, HttpServletResponse response,
			RedirectAttributes redirect) {
		if (NewAccountSignIn.signedIn(authentication)) {
			return alreadySignedIn(authentication, redirect);
		}
		form.check(result);
		if (result.hasErrors()) {
			return page(model);
		}
		DeliveryPartnerProfile profile;
		try {
			profile = onboarding.register(form);
		}
		catch (EmailTakenException ex) {
			result.rejectValue("email", "taken",
					"This email already has a Zaika account. Use another email to ride with us.");
			return page(model);
		}
		User rider = profile.getUser();
		redirect.addFlashAttribute("flashSuccess",
				"Thanks, " + rider.firstName() + "! Your rider sign-up is in and waiting for review.");
		return "redirect:" + newAccountSignIn.signIn(rider, request, response, redirect);
	}

	private static String page(Model model) {
		model.addAttribute("vehicleTypes", VehicleType.values());
		model.addAttribute("areas", RiderSignupForm.AREAS);
		return "partner/onboarding";
	}

	private static String alreadySignedIn(Authentication authentication, RedirectAttributes redirect) {
		String landing = NewAccountSignIn.landingPage(authentication);
		if (!"/partner".equals(landing)) {
			redirect.addFlashAttribute("flashInfo", "You're signed in as " + authentication.getName()
					+ ". Riding needs its own partner account: log out, then sign up as a delivery partner.");
		}
		return "redirect:" + landing;
	}

}
