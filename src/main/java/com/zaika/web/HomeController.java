package com.zaika.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.catalog.RestaurantBrowseService;

/** The home page: hero search, cuisine shortcuts, the restaurant rows, how it works and the partner band. */
@Controller
class HomeController {

	private final RestaurantBrowseService browse;

	HomeController(RestaurantBrowseService browse) {
		this.browse = browse;
	}

	@GetMapping("/")
	String home(Model model) {
		model.addAttribute("home", browse.highlights());
		model.addAttribute("cuisines", browse.liveCuisines());
		return "home";
	}

}
