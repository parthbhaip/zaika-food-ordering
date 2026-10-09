package com.zaika.owner;

import java.util.List;

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

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.Restaurant;
import com.zaika.security.NewAccountSignIn;
import com.zaika.storage.ImageKind;
import com.zaika.storage.InvalidImageException;
import com.zaika.storage.StorageService;
import com.zaika.user.EmailTakenException;
import com.zaika.user.User;

/**
 * "Register your restaurant" ({@code /partner-with-us/restaurant}, public): one form creates the owner account and
 * a PENDING restaurant, then signs the owner in and takes them to their dashboard, which shows the review status.
 * Signed-in visitors are sent back to their own area: a restaurant needs its own partner account.
 */
@Controller
@RequestMapping(RestaurantOnboardingController.PATH)
class RestaurantOnboardingController {

	static final String PATH = "/partner-with-us/restaurant";

	private final RestaurantOnboardingService onboarding;

	private final StorageService storage;

	private final NewAccountSignIn newAccountSignIn;

	RestaurantOnboardingController(RestaurantOnboardingService onboarding, StorageService storage,
			NewAccountSignIn newAccountSignIn) {
		this.onboarding = onboarding;
		this.storage = storage;
		this.newAccountSignIn = newAccountSignIn;
	}

	@GetMapping
	String form(Authentication authentication, Model model, RedirectAttributes redirect) {
		if (NewAccountSignIn.signedIn(authentication)) {
			return alreadySignedIn(authentication, redirect);
		}
		model.addAttribute("form", new RestaurantSignupForm());
		return page(model);
	}

	@PostMapping
	String register(@Valid @ModelAttribute("form") RestaurantSignupForm form, BindingResult result,
			Authentication authentication, Model model, HttpServletRequest request, HttpServletResponse response,
			RedirectAttributes redirect) {
		if (NewAccountSignIn.signedIn(authentication)) {
			return alreadySignedIn(authentication, redirect);
		}
		form.check(result);
		List<Cuisine> options = onboarding.cuisineOptions();
		if (!result.hasFieldErrors("cuisineIds") && form.getCuisineIds().stream()
			.anyMatch(id -> options.stream().noneMatch(c -> c.getId().equals(id)))) {
			result.rejectValue("cuisineIds", "unknown", "Pick your cuisines from the list.");
		}
		if (result.hasErrors()) {
			return page(model, options);
		}
		String logoPath = null;
		if (form.hasLogo()) {
			try {
				logoPath = storage.store(ImageKind.LOGO, form.getLogo());
			}
			catch (InvalidImageException ex) {
				result.rejectValue("logo", "invalid", ex.getMessage());
				return page(model, options);
			}
		}
		Restaurant restaurant;
		try {
			restaurant = onboarding.register(form, logoPath);
		}
		catch (EmailTakenException ex) {
			storage.delete(logoPath);
			result.rejectValue("email", "taken",
					"This email already has a Zaika account. Use another email for your restaurant.");
			return page(model, options);
		}
		catch (RuntimeException ex) {
			storage.delete(logoPath); // nothing was saved (e.g. the slug was taken meanwhile): no orphan file
			throw ex;
		}
		User owner = restaurant.getOwner();
		redirect.addFlashAttribute("flashSuccess", "Thanks, " + owner.firstName() + "! "
				+ restaurant.getName() + " is registered and waiting for review.");
		return "redirect:" + newAccountSignIn.signIn(owner, request, response, redirect);
	}

	private String page(Model model) {
		return page(model, onboarding.cuisineOptions());
	}

	private static String page(Model model, List<Cuisine> cuisines) {
		model.addAttribute("cuisines", cuisines);
		model.addAttribute("prepMinutes", RestaurantSignupForm.PREP_MINUTES);
		model.addAttribute("maxCuisines", RestaurantSignupForm.MAX_CUISINES);
		return "owner/onboarding";
	}

	private static String alreadySignedIn(Authentication authentication, RedirectAttributes redirect) {
		String landing = NewAccountSignIn.landingPage(authentication);
		if (!"/owner".equals(landing)) {
			redirect.addFlashAttribute("flashInfo", "You're signed in as " + authentication.getName()
					+ ". A restaurant needs its own partner account: log out, then register your restaurant.");
		}
		return "redirect:" + landing;
	}

}
