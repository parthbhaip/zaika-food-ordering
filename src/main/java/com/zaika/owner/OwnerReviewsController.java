package com.zaika.owner;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.common.Requests;
import com.zaika.review.ReplyForm;
import com.zaika.review.ReviewException;
import com.zaika.review.ReviewService;
import com.zaika.review.ReviewService.OwnerReviews;
import com.zaika.security.CurrentUser;

/**
 * The owner's reviews ({@code /owner/reviews}): every review of their restaurant, newest first, 10 a page (canonical
 * {@code ?page=}), and one public reply per review (plain Post/Redirect/Get; a failed reply re-renders the page with
 * that reply box open and the text kept).
 */
@Controller
class OwnerReviewsController {

	static final String VIEW = "owner/reviews";

	private static final String BASE = "/owner/reviews";

	private final ReviewService reviews;

	OwnerReviewsController(ReviewService reviews) {
		this.reviews = reviews;
	}

	@GetMapping(BASE)
	String list(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String page,
			HttpServletRequest request, Model model) {
		int asked = Math.max(1, Requests.pageNumber(page));
		OwnerReviews data = reviews.forOwner(me.getId(), asked - 1).orElse(null);
		int last = data == null ? 1 : Math.max(data.reviews().getTotalPages(), 1);
		String canonical = url(Math.min(asked, last));
		if (!canonical.equals(Requests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		show(data, new ReplyForm(), null, model);
		return VIEW;
	}

	/** {@code page} is the list page the reply was written on, so the answer goes back there. */
	@PostMapping(BASE + "/{id}/reply")
	String reply(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@RequestParam(required = false) String page, @Validated @ModelAttribute("replyForm") ReplyForm form,
			BindingResult errors, Model model, RedirectAttributes redirect) {
		int number = Math.max(1, Requests.pageNumber(page));
		if (errors.hasErrors()) {
			if (reviews.ownedReview(me.getId(), id).getReply() == null) { // 404 for another restaurant's review
				show(reviews.forOwner(me.getId(), number - 1).orElseThrow(), form, id, model);
				return VIEW;
			}
			redirect.addFlashAttribute("flashError", ReviewService.ALREADY_REPLIED);
			return "redirect:" + url(number) + "#review-" + id;
		}
		try {
			reviews.reply(me.getId(), id, form.getReply());
			redirect.addFlashAttribute("flashSuccess", "Reply posted. Customers see it under the review.");
		}
		catch (ReviewException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:" + url(number) + "#review-" + id;
	}

	private static void show(OwnerReviews data, ReplyForm form, Long replyTo, Model model) {
		model.addAttribute("data", data);
		model.addAttribute("replyForm", form);
		model.addAttribute("replyTo", replyTo);
		model.addAttribute("base", BASE);
	}

	private static String url(int page) {
		return page > 1 ? BASE + "?page=" + page : BASE;
	}

}
