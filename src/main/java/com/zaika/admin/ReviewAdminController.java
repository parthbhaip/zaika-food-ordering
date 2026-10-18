package com.zaika.admin;

import java.util.EnumMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.common.Requests;
import com.zaika.review.Review;
import com.zaika.review.ReviewRepository.VisibilityCount;
import com.zaika.review.ReviewService;

/**
 * Reviews in the admin panel ({@code /admin/reviews}): every review newest first, 20 a page, Shown / Hidden chips and a
 * search over restaurant, customer and comment, with Hide and Show again (plain Post/Redirect/Get back to the same
 * list). A hidden review leaves the restaurant page and its rating.
 */
@Controller
class ReviewAdminController {

	static final String VIEW = "admin/reviews";

	private final ReviewService reviews;

	ReviewAdminController(ReviewService reviews) {
		this.reviews = reviews;
	}

	@GetMapping(ModerationFilter.REVIEWS)
	String list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, HttpServletRequest request, Model model) {
		ModerationFilter<ReviewVisibility> filter = ModerationFilter.reviews(status, q, page);
		Boolean hidden = filter.getStatus() == null ? null : filter.getStatus() == ReviewVisibility.HIDDEN;
		Page<Review> found = reviews.adminList(hidden, filter.getQ(), filter.getPage() - 1);
		filter.setPage(Math.min(filter.getPage(), Math.max(found.getTotalPages(), 1)));
		if (!filter.url().equals(Requests.currentUrl(request))) {
			return "redirect:" + filter.url();
		}
		Map<ReviewVisibility, Long> counts = new EnumMap<>(ReviewVisibility.class);
		for (VisibilityCount c : reviews.countPerVisibility()) {
			counts.put(c.hidden() ? ReviewVisibility.HIDDEN : ReviewVisibility.SHOWN, c.count());
		}
		model.addAttribute("filter", filter);
		model.addAttribute("queue", Queue.of(found, ReviewVisibility.values(), counts));
		model.addAttribute("visibilities", ReviewVisibility.values());
		return VIEW;
	}

	@PostMapping(ModerationFilter.REVIEWS + "/{id}/hide")
	String hide(@PathVariable long id, @RequestParam(required = false) String status,
			@RequestParam(required = false) String q, @RequestParam(required = false) String page,
			RedirectAttributes redirect) {
		return change(id, true, ModerationFilter.reviews(status, q, page), redirect);
	}

	@PostMapping(ModerationFilter.REVIEWS + "/{id}/show")
	String show(@PathVariable long id, @RequestParam(required = false) String status,
			@RequestParam(required = false) String q, @RequestParam(required = false) String page,
			RedirectAttributes redirect) {
		return change(id, false, ModerationFilter.reviews(status, q, page), redirect);
	}

	/** Back to the list the admin was on (rebuilt from its parameters, so never somewhere else). */
	private String change(long id, boolean hide, ModerationFilter<ReviewVisibility> back, RedirectAttributes redirect) {
		if (reviews.setHidden(id, hide)) {
			redirect.addFlashAttribute("flashSuccess", hide
					? "Review hidden. It's off the restaurant page and no longer counts towards the rating."
					: "Review shown again. It's back on the restaurant page and counts towards the rating.");
		}
		else {
			redirect.addFlashAttribute("flashInfo", hide ? "This review is already hidden." : "This review is already shown.");
		}
		return "redirect:" + back.url();
	}

}
