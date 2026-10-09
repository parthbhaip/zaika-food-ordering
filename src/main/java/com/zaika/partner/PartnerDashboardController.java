package com.zaika.partner;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.delivery.RiderOnboardingService;
import com.zaika.security.CurrentUser;

/** The rider's home ({@code /partner}): review status and the rider's own details. */
@Controller
class PartnerDashboardController {

	private final RiderOnboardingService riders;

	PartnerDashboardController(RiderOnboardingService riders) {
		this.riders = riders;
	}

	@GetMapping("/partner")
	String dashboard(@AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("profile", riders.profileOf(me.getId()).orElse(null));
		return "partner/dashboard";
	}

}
