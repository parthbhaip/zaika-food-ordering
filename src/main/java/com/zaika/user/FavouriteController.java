package com.zaika.user;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.security.CurrentUser;

@Controller
@RequestMapping("/account/favourites")
class FavouriteController {

	private final FavouriteService favourites;

	FavouriteController(FavouriteService favourites) {
		this.favourites = favourites;
	}

	@GetMapping
	String list(@AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("favourites", favourites.list(me.getId()));
		return "account/favourites";
	}

	@PostMapping("/{slug}")
	String toggle(@AuthenticationPrincipal CurrentUser me, @PathVariable String slug, HttpServletRequest request,
			@RequestParam(required = false) String returnTo, Model model, RedirectAttributes redirect) {
		boolean saved = favourites.toggle(me.getId(), slug);
		if ("true".equals(request.getHeader("HX-Request"))) {
			model.addAttribute("favouriteSlug", slug);
			model.addAttribute("favouriteSaved", saved);
			model.addAttribute("favouriteMessage", saved ? "Added to favourites." : "Removed from favourites.");
			return "fragments/favourite :: heart";
		}
		redirect.addFlashAttribute("flashSuccess", saved ? "Added to favourites." : "Removed from favourites.");
		return "redirect:" + ("/account/favourites".equals(returnTo) ? returnTo : "/r/" + slug);
	}

}
