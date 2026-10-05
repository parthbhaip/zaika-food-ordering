package com.zaika.catalog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * {@code /r/{slug}}: a restaurant's header and menu. The menu search and the "Pure veg only" toggle are a plain GET
 * form; with htmx the same form swaps only the menu ({@code HX-Request} gets the {@code htmx-menu} fragment and an
 * {@code HX-Push-Url} with the canonical URL). Full-page requests for a non-canonical URL are redirected to it.
 */
@Controller
class RestaurantPageController {

	private final RestaurantMenuService menus;

	RestaurantPageController(RestaurantMenuService menus) {
		this.menus = menus;
	}

	@GetMapping("/r/{slug}")
	String page(@PathVariable String slug, @ModelAttribute("filter") MenuFilter filter, BindingResult unparseable,
			HttpServletRequest request, HttpServletResponse response, Model model) {
		RestaurantMenu menu = menus.menu(slug, filter); // 404 before any redirect for unknown restaurants
		boolean htmx = "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));
		response.addHeader("Vary", "HX-Request");
		String canonical = filter.url(menu.restaurant().getSlug());
		if (htmx) {
			response.setHeader("HX-Push-Url", canonical);
		}
		else if (!canonical.equals(CatalogRequests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		model.addAttribute("menu", menu);
		return htmx ? "catalog/restaurant :: htmx-menu" : "catalog/restaurant";
	}

}
