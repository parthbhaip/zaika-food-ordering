package com.zaika.catalog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.common.Requests;
import com.zaika.review.ReviewService;
import com.zaika.security.CurrentUser;
import com.zaika.user.FavouriteService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * {@code /r/{slug}}: a restaurant's header and menu. The menu search and the "Pure veg only" toggle are a plain GET
 * form; with htmx the same form swaps only the menu ({@code HX-Request} gets the {@code htmx-menu} fragment and an
 * {@code HX-Push-Url} with the canonical URL). Full-page requests for a non-canonical URL are redirected to it.
 * Dishes already in the visitor's cart show a quantity stepper instead of ADD. Below the menu, the newest reviews
 * link to the full list ({@code /r/{slug}/reviews}).
 */
@Controller
class RestaurantPageController {

	private final RestaurantMenuService menus;

	private final CartService carts;

	private final ReviewService reviews;

	private final FavouriteService favourites;

	RestaurantPageController(RestaurantMenuService menus, CartService carts, ReviewService reviews,
			FavouriteService favourites) {
		this.menus = menus;
		this.carts = carts;
		this.reviews = reviews;
		this.favourites = favourites;
	}

	@GetMapping("/r/{slug}")
	String page(@PathVariable String slug, @ModelAttribute("filter") MenuFilter filter, BindingResult unparseable,
			HttpServletRequest request, HttpServletResponse response, @AuthenticationPrincipal CurrentUser me, Model model) {
		RestaurantMenu menu = menus.menu(slug, filter); // 404 before any redirect for unknown restaurants
		boolean htmx = "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));
		response.addHeader("Vary", "HX-Request");
		String canonical = filter.url(menu.restaurant().getSlug());
		if (htmx) {
			response.setHeader("HX-Push-Url", canonical);
		}
		else if (!canonical.equals(Requests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		model.addAttribute("menu", menu);
		CartOwner cartOwner = CartOwner.of(request, false);
		if (cartOwner != null) {
			model.addAttribute("cartQuantities", carts.quantities(cartOwner));
		}
		if (htmx) {
			return "catalog/restaurant :: htmx-menu";
		}
		model.addAttribute("reviews", reviews.latest(menu.restaurant().getId()));
		boolean customer = me != null
				&& me.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()));
		model.addAttribute("favouriteCustomer", customer);
		if (customer) {
			model.addAttribute("favouriteSlug", menu.restaurant().getSlug());
			model.addAttribute("favouriteSaved", favourites.contains(me.getId(), menu.restaurant().getId()));
		}
		return "catalog/restaurant";
	}

}
