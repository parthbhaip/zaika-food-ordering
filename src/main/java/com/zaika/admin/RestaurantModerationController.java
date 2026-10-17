package com.zaika.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.admin.RestaurantModerationService.Decision;
import com.zaika.admin.RestaurantModerationService.Detail;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Requests;

/**
 * Restaurant moderation ({@code /admin/restaurants}): the list with status chips and search, the review page per
 * restaurant ({@code /admin/restaurants/{slug}}) and its decisions, all plain Post/Redirect/Get. Reject and suspend
 * need a reason; a missing one re-renders the review page with that form open and the error inline.
 */
@Controller
class RestaurantModerationController {

	static final String LIST_VIEW = "admin/restaurants";

	static final String DETAIL_VIEW = "admin/restaurant";

	private final RestaurantModerationService moderation;

	RestaurantModerationController(RestaurantModerationService moderation) {
		this.moderation = moderation;
	}

	@GetMapping(ModerationFilter.BASE)
	String list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, HttpServletRequest request, Model model) {
		ModerationFilter<RestaurantStatus> filter = ModerationFilter.of(status, q, page);
		Queue<Restaurant, RestaurantStatus> queue = moderation.list(filter);
		filter.setPage(Math.min(filter.getPage(), Math.max(queue.page().getTotalPages(), 1)));
		if (!filter.url().equals(Requests.currentUrl(request))) {
			return "redirect:" + filter.url();
		}
		model.addAttribute("filter", filter);
		model.addAttribute("queue", queue);
		model.addAttribute("statuses", RestaurantStatus.values());
		return LIST_VIEW;
	}

	@GetMapping(ModerationFilter.BASE + "/{slug}")
	String detail(@PathVariable String slug, Model model) {
		return detailPage(slug, new ModerationReasonForm(), null, model);
	}

	@PostMapping(ModerationFilter.BASE + "/{slug}/approve")
	String approve(@PathVariable String slug, RedirectAttributes redirect) {
		return decide(slug, Decision.APPROVE, null, redirect);
	}

	@PostMapping(ModerationFilter.BASE + "/{slug}/reactivate")
	String reactivate(@PathVariable String slug, RedirectAttributes redirect) {
		return decide(slug, Decision.REACTIVATE, null, redirect);
	}

	@PostMapping(ModerationFilter.BASE + "/{slug}/reject")
	String reject(@PathVariable String slug, @Valid @ModelAttribute("reasonForm") ModerationReasonForm form,
			BindingResult errors, Model model, RedirectAttributes redirect) {
		return errors.hasErrors() ? withReasonError(slug, form, Decision.REJECT, model, redirect)
				: decide(slug, Decision.REJECT, form.getReason(), redirect);
	}

	@PostMapping(ModerationFilter.BASE + "/{slug}/suspend")
	String suspend(@PathVariable String slug, @Valid @ModelAttribute("reasonForm") ModerationReasonForm form,
			BindingResult errors, Model model, RedirectAttributes redirect) {
		return errors.hasErrors() ? withReasonError(slug, form, Decision.SUSPEND, model, redirect)
				: decide(slug, Decision.SUSPEND, form.getReason(), redirect);
	}

	/**
	 * A reject or suspend without a valid reason: the review page again with that form open, unless the decision no
	 * longer fits the status (the restaurant changed meanwhile), which gets the usual refusal instead.
	 */
	private String withReasonError(String slug, ModerationReasonForm form, Decision decision, Model model,
			RedirectAttributes redirect) {
		Detail d = moderation.detail(slug);
		if (!decision.appliesTo(d.restaurant().getStatus())) {
			return decide(slug, decision, null, redirect);
		}
		model.addAttribute("d", d);
		model.addAttribute("reasonForm", form);
		model.addAttribute("open", decision);
		return DETAIL_VIEW;
	}

	private String detailPage(String slug, ModerationReasonForm form, Decision open, Model model) {
		model.addAttribute("d", moderation.detail(slug));
		model.addAttribute("reasonForm", form);
		model.addAttribute("open", open);
		return DETAIL_VIEW;
	}

	private String decide(String slug, Decision decision, String reason, RedirectAttributes redirect) {
		try {
			String name = moderation.decide(slug, decision, reason);
			redirect.addFlashAttribute("flashSuccess", switch (decision) {
				case APPROVE -> name + " is approved and live. We've emailed the owner.";
				case REJECT -> name + " is rejected. We've emailed the owner the reason.";
				case SUSPEND -> name + " is suspended and hidden from customers. We've emailed the owner.";
				case REACTIVATE -> name + " is live again. We've emailed the owner.";
			});
		}
		catch (ModerationException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:" + ModerationFilter.BASE + "/" + slug;
	}

}
