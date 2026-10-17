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

import com.zaika.admin.RiderModerationService.Decision;
import com.zaika.admin.RiderModerationService.Detail;
import com.zaika.common.Requests;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.RiderStatus;

/**
 * Rider moderation ({@code /admin/riders}): the list with status chips and search, the review page per rider
 * ({@code /admin/riders/{userId}}) and its two decisions, plain Post/Redirect/Get. Suspending needs a reason; a missing
 * one re-renders the review page with the form open and the error inline.
 */
@Controller
class RiderModerationController {

	static final String LIST_VIEW = "admin/riders";

	static final String DETAIL_VIEW = "admin/rider";

	private final RiderModerationService moderation;

	RiderModerationController(RiderModerationService moderation) {
		this.moderation = moderation;
	}

	@GetMapping(ModerationFilter.RIDERS)
	String list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, HttpServletRequest request, Model model) {
		ModerationFilter<RiderStatus> filter = ModerationFilter.riders(status, q, page);
		Queue<DeliveryPartnerProfile, RiderStatus> queue = moderation.list(filter);
		filter.setPage(Math.min(filter.getPage(), Math.max(queue.page().getTotalPages(), 1)));
		if (!filter.url().equals(Requests.currentUrl(request))) {
			return "redirect:" + filter.url();
		}
		model.addAttribute("filter", filter);
		model.addAttribute("queue", queue);
		model.addAttribute("statuses", RiderStatus.values());
		return LIST_VIEW;
	}

	@GetMapping(ModerationFilter.RIDERS + "/{id}")
	String detail(@PathVariable long id, Model model) {
		model.addAttribute("d", moderation.detail(id));
		model.addAttribute("reasonForm", new ModerationReasonForm());
		model.addAttribute("open", false);
		return DETAIL_VIEW;
	}

	@PostMapping(ModerationFilter.RIDERS + "/{id}/approve")
	String approve(@PathVariable long id, RedirectAttributes redirect) {
		return decide(id, Decision.APPROVE, null, redirect);
	}

	@PostMapping(ModerationFilter.RIDERS + "/{id}/suspend")
	String suspend(@PathVariable long id, @Valid @ModelAttribute("reasonForm") ModerationReasonForm form,
			BindingResult errors, Model model, RedirectAttributes redirect) {
		if (!errors.hasErrors()) {
			return decide(id, Decision.SUSPEND, form.getReason(), redirect);
		}
		Detail d = moderation.detail(id);
		if (!Decision.SUSPEND.appliesTo(d.profile().getStatus())) {
			return decide(id, Decision.SUSPEND, null, redirect); // changed meanwhile: the refusal wins
		}
		model.addAttribute("d", d);
		model.addAttribute("open", true);
		return DETAIL_VIEW;
	}

	private String decide(long id, Decision decision, String reason, RedirectAttributes redirect) {
		try {
			String name = moderation.decide(id, decision, reason);
			redirect.addFlashAttribute("flashSuccess", decision == Decision.APPROVE
					? name + " is approved and can go online. We've emailed them."
					: name + " is suspended and offline. We've emailed them the reason.");
		}
		catch (ModerationException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:" + ModerationFilter.RIDERS + "/" + id;
	}

}
