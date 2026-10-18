package com.zaika.review;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.zaika.common.Requests;

/**
 * {@code /r/{slug}/reviews}: a restaurant's public reviews, {@link ReviewService#PAGE_SIZE} to a page, newest first or
 * highest rated first ({@code ?sort=highest}). Non-canonical URLs ({@code sort=newest}, {@code page=1}, junk, a page
 * past the end) redirect to the canonical one.
 */
@Controller
class RestaurantReviewsController {

	static final String VIEW = "catalog/reviews";

	private final ReviewService reviews;

	RestaurantReviewsController(ReviewService reviews) {
		this.reviews = reviews;
	}

	@GetMapping("/r/{slug}/reviews")
	String reviews(@PathVariable String slug, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String page, HttpServletRequest request, Model model) {
		ReviewSort order = ReviewSort.parse(sort);
		if (order == null) {
			order = ReviewSort.NEWEST;
		}
		int asked = Math.max(1, Requests.pageNumber(page));
		ReviewService.Listing listing = reviews.listing(slug, order, asked - 1); // 404 first for unknown restaurants
		int last = Math.max(listing.reviews().getTotalPages(), 1);
		String base = url(slug, order);
		String canonical = Math.min(asked, last) > 1 ? base + (base.contains("?") ? "&" : "?") + "page=" + Math.min(asked, last) : base;
		if (!canonical.equals(Requests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		model.addAttribute("listing", listing);
		model.addAttribute("sort", order);
		model.addAttribute("sorts", ReviewSort.values());
		model.addAttribute("base", base);
		return VIEW;
	}

	/** The first page of the reviews in {@code sort}: {@code /r/rassa-darbar/reviews?sort=highest}. */
	static String url(String slug, ReviewSort sort) {
		return "/r/" + slug + "/reviews" + (sort == ReviewSort.NEWEST ? "" : "?sort=" + sort.slug());
	}

}
