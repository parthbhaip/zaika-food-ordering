package com.zaika.partner;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.zaika.common.Requests;
import com.zaika.delivery.RiderEarningsService;
import com.zaika.delivery.RiderEarningsService.Earnings;
import com.zaika.security.CurrentUser;

/**
 * The rider's earnings ({@code /partner/earnings}): today, this week and this month, cash in hand and the delivery
 * history, 20 to a page. Non-canonical page numbers ({@code page=1}, junk, past the end) redirect.
 */
@Controller
class PartnerEarningsController {

	private static final String BASE = "/partner/earnings";

	private final RiderEarningsService earnings;

	PartnerEarningsController(RiderEarningsService earnings) {
		this.earnings = earnings;
	}

	@GetMapping(BASE)
	String earnings(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String page,
			Model model) {
		int number = Requests.pageNumber(page);
		if (number < 1 || "1".equals(page)) { // unreadable, out of range, or spelling out the default
			return "redirect:" + BASE;
		}
		Earnings e = earnings.earnings(me.getId(), number - 1).orElse(null);
		if (number > 1 && (e == null || number > e.history().getTotalPages())) {
			int last = e == null ? 1 : e.history().getTotalPages();
			return last > 1 ? "redirect:" + BASE + "?page=" + last : "redirect:" + BASE;
		}
		model.addAttribute("e", e);
		return "partner/earnings";
	}

}
