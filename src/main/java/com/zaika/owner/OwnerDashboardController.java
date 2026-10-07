package com.zaika.owner;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.security.CurrentUser;

/** The owner's home ({@code /owner}): the restaurant's review status and what customers will see. */
@Controller
class OwnerDashboardController {

	private final OwnerDashboardService dashboard;

	OwnerDashboardController(OwnerDashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/owner")
	String dashboard(@AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("restaurant", dashboard.restaurantOf(me.getId()).orElse(null));
		return "owner/dashboard";
	}

}
