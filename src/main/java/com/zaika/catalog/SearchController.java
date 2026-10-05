package com.zaika.catalog;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@code /search?q=}: matching restaurants and dishes in two groups, and {@code /search/suggest?q=}: the header's
 * type-ahead list (an htmx fragment). Non-canonical search URLs ({@code ?q=}, padded text) redirect to the canonical
 * one; a suggest request without htmx (JavaScript off, a pasted link) redirects to the full search.
 */
@Controller
class SearchController {

	private final SearchService search;

	private final RestaurantBrowseService browse;

	SearchController(SearchService search, RestaurantBrowseService browse) {
		this.search = search;
		this.browse = browse;
	}

	@GetMapping("/search")
	String search(@RequestParam(required = false) String q, HttpServletRequest request, Model model) {
		String query = SearchService.normalize(q);
		String canonical = SearchService.url(query);
		if (!canonical.equals(RestaurantListController.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		SearchService.Results results = query == null ? null : search.search(query);
		model.addAttribute("q", query);
		model.addAttribute("results", results);
		if (results == null || results.isEmpty()) {
			model.addAttribute("cuisines", browse.cuisines());
		}
		return "catalog/search";
	}

	@GetMapping("/search/suggest")
	String suggest(@RequestParam(required = false) String q, HttpServletRequest request,
			HttpServletResponse response, Model model) {
		response.addHeader("Vary", "HX-Request");
		String query = SearchService.normalize(q);
		if (!"true".equals(request.getHeader("HX-Request"))) {
			return "redirect:" + SearchService.url(query);
		}
		List<SearchService.Suggestion> suggestions = search.suggest(query);
		model.addAttribute("q", query);
		model.addAttribute("suggestions", suggestions);
		model.addAttribute("showAll", query != null && query.length() >= SearchService.MIN_SUGGEST_LENGTH);
		return "catalog/search :: suggestions";
	}

}
