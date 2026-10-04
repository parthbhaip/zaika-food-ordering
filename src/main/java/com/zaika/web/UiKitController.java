package com.zaika.web;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Living style guide for the design system. Exists only in the dev profile; everywhere else the URL is a 404. */
@Controller
@Profile("dev")
class UiKitController {

	static final List<String> ICONS = List.of("search", "cart", "user", "location", "clock", "star", "star-filled",
			"heart", "plus", "minus", "trash", "chevron-down", "chevron-right", "check", "x", "menu", "bell", "filter",
			"bike", "store", "receipt", "logout", "edit", "upload", "phone", "info", "leaf");

	@GetMapping("/dev/ui-kit")
	String uiKit(Model model) {
		model.addAttribute("icons", ICONS);
		return "dev/ui-kit";
	}

}
