package com.zaika.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.config.SeedPhotoCredits;

/** {@code /photo-credits}: who took the restaurant and dish photos that ship with the app, and under which license. */
@Controller
class PhotoCreditsController {

	private final SeedPhotoCredits credits;

	PhotoCreditsController(SeedPhotoCredits credits) {
		this.credits = credits;
	}

	@GetMapping("/photo-credits")
	String credits(Model model) {
		model.addAttribute("restaurants", credits.restaurants());
		model.addAttribute("dishes", credits.dishes());
		return "pages/photo-credits";
	}

}
