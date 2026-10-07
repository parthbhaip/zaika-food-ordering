package com.zaika.owner;

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

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.owner.RestaurantProfileService.Profile;
import com.zaika.security.CurrentUser;

/**
 * The owner's restaurant profile ({@code /owner/restaurant}): one form for details, cuisines, photos, hours and fees,
 * saved with Post/Redirect/Get. It uploads photos, so it posts to the URL that shows it (see
 * {@code common.UploadLimitFilter}). Errors re-render the page with the typed values kept.
 */
@Controller
@RequestMapping("/owner/restaurant")
class RestaurantProfileController {

	private final RestaurantProfileService profiles;

	RestaurantProfileController(RestaurantProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	String profile(@AuthenticationPrincipal CurrentUser me, Model model) {
		Profile profile = profiles.profile(me.getId());
		model.addAttribute("form", RestaurantProfileForm.of(profile.restaurant()));
		return page(model, profile);
	}

	@PostMapping
	String save(@Valid @ModelAttribute("form") RestaurantProfileForm form, BindingResult result,
			@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				Restaurant restaurant = profiles.update(me.getId(), form);
				redirect.addFlashAttribute("flashSuccess", restaurant.getStatus() == RestaurantStatus.APPROVED
						? "Profile saved. Customers see the changes right away."
						: "Profile saved. Customers will see it once your restaurant is approved.");
				return "redirect:/owner/restaurant";
			}
			catch (OwnerRuleException ex) {
				result.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
		}
		return page(model, profiles.profile(me.getId()));
	}

	private static String page(Model model, Profile profile) {
		model.addAttribute("profile", profile);
		model.addAttribute("maxCuisines", RestaurantProfileForm.MAX_CUISINES);
		return "owner/restaurant";
	}

}
