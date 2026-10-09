package com.zaika.catalog;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.zaika.common.Requests;

/**
 * {@code /restaurants}: the card grid with filters, search, sort and pages. A plain GET form drives it; with htmx the
 * same form swaps only the results ({@code HX-Request} gets the {@code htmx-results} fragment and an
 * {@code HX-Push-Url} with the canonical URL). Full-page requests for a non-canonical URL (empty parameters, defaults,
 * a page past the end) are redirected to the canonical one.
 */
@Controller
class RestaurantListController {

	private final RestaurantBrowseService browse;

	RestaurantListController(RestaurantBrowseService browse) {
		this.browse = browse;
	}

	@GetMapping("/restaurants")
	String list(@ModelAttribute("filter") RestaurantFilter filter, BindingResult unparseable,
			HttpServletRequest request, HttpServletResponse response, Model model) {
		// Values that do not parse (veg=maybe, page=two) were left at their defaults; the redirect below drops them.
		List<Cuisine> cuisines = browse.cuisines();
		filter.keepKnownCuisine(cuisines.stream().map(Cuisine::getSlug).toList());
		boolean htmx = "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));

		Page<RestaurantCard> page = browse.browse(filter);
		if (filter.getPage() > Math.max(page.getTotalPages(), 1)) {
			filter.setPage(Math.max(page.getTotalPages(), 1));
			if (htmx) {
				page = browse.browse(filter);
			}
		}

		response.addHeader("Vary", "HX-Request");
		String canonical = filter.url();
		if (htmx) {
			response.setHeader("HX-Push-Url", canonical);
		}
		else if (!canonical.equals(Requests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		model.addAttribute("page", page);
		model.addAttribute("cuisines", cuisines);
		model.addAttribute("sorts", RestaurantSort.values());
		return htmx ? "catalog/restaurants :: htmx-results" : "catalog/restaurants";
	}

}
