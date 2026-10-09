package com.zaika.owner;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.owner.OwnerDashboardService.AcceptingOrders;
import com.zaika.security.CurrentUser;

/**
 * The owner's home ({@code /owner}): review status, the accepting-orders switch, today's numbers, latest orders and
 * the 7-day revenue chart. The switch is a plain form (Post/Redirect/Get with a toast); with htmx it swaps only its
 * own card and adds the toast out of band.
 */
@Controller
class OwnerDashboardController {

	private final OwnerDashboardService dashboard;

	OwnerDashboardController(OwnerDashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/owner")
	String dashboard(@AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("dashboard", dashboard.dashboard(me.getId()).orElse(null));
		return "owner/dashboard";
	}

	@PostMapping("/owner/accepting")
	String accepting(@RequestParam boolean accepting, @AuthenticationPrincipal CurrentUser me,
			HttpServletRequest request, HttpServletResponse response, Model model, RedirectAttributes redirect) {
		boolean htmx = "true".equals(request.getHeader("HX-Request"));
		AcceptingOrders state;
		try {
			state = dashboard.setAcceptingOrders(me.getId(), accepting);
		}
		catch (OwnerRuleException ex) {
			if (!htmx) {
				redirect.addFlashAttribute("flashError", ex.getMessage());
				return "redirect:/owner";
			}
			response.setHeader("HX-Retarget", "#toasts");
			response.setHeader("HX-Reswap", "beforeend");
			model.addAttribute("message", ex.getMessage());
			return "fragments/flash :: htmx-error";
		}
		String message = accepting ? "You're taking orders again." : "New orders paused. Turn it back on any time.";
		if (!htmx) {
			redirect.addFlashAttribute(accepting ? "flashSuccess" : "flashInfo", message);
			return "redirect:/owner";
		}
		model.addAttribute("accepting", state);
		model.addAttribute("toastKind", accepting ? "success" : "info");
		model.addAttribute("toastMessage", message);
		return "owner/dashboard :: accepting-update";
	}

}
