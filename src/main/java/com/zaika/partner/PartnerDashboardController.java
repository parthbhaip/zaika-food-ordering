package com.zaika.partner;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.delivery.DeliveryRuleException;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.DeliveryService.Dashboard;
import com.zaika.order.Order;
import com.zaika.security.CurrentUser;

/**
 * The rider's home ({@code /partner}): review status, the online switch, the current delivery and the orders waiting
 * for a rider. The switch and Claim are plain forms (Post/Redirect/Get with a toast); the list of waiting orders
 * polls with htmx (see {@link #dashboard}).
 */
@Controller
class PartnerDashboardController {

	private final DeliveryService deliveries;

	PartnerDashboardController(DeliveryService deliveries) {
		this.deliveries = deliveries;
	}

	/**
	 * The page, or for the htmx poll ({@code ?v=} the version the list shows): 204 while the waiting orders are
	 * unchanged, the {@code available} fragment when they changed, and {@code HX-Refresh} when the list no longer
	 * belongs on the page (offline, suspended, or a delivery claimed in another tab), so the page never goes stale.
	 */
	@GetMapping("/partner")
	String dashboard(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String v,
			HttpServletRequest request, HttpServletResponse response, Model model) {
		response.addHeader("Vary", "HX-Request");
		Dashboard d = deliveries.dashboard(me.getId()).orElse(null);
		if (htmx(request)) {
			if (d == null || !d.onDuty() || d.active() != null) {
				response.setHeader("HX-Refresh", "true");
				response.setStatus(HttpStatus.NO_CONTENT.value());
				return null;
			}
			if (d.version().equals(v)) {
				response.setStatus(HttpStatus.NO_CONTENT.value());
				return null; // handled: htmx swaps nothing on a 204
			}
		}
		model.addAttribute("d", d);
		return htmx(request) ? "partner/dashboard :: available" : "partner/dashboard";
	}

	@PostMapping("/partner/online")
	String online(@AuthenticationPrincipal CurrentUser me, @RequestParam boolean online,
			RedirectAttributes redirect) {
		try {
			deliveries.setOnline(me.getId(), online);
			redirect.addFlashAttribute(online ? "flashSuccess" : "flashInfo",
					online ? "You're online. Orders waiting for a rider show up below."
							: "You're offline. You won't see new orders until you go online again.");
		}
		catch (DeliveryRuleException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:/partner";
	}

	@PostMapping("/partner/orders/{code}/claim")
	String claim(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, RedirectAttributes redirect) {
		try {
			Order order = deliveries.claim(me.getId(), code);
			redirect.addFlashAttribute("flashSuccess",
					code + " is yours. Head to " + order.getRestaurant().getName() + " for the pickup.");
			return "redirect:/partner#current-delivery";
		}
		catch (DeliveryRuleException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
			return "redirect:/partner";
		}
	}

	private static boolean htmx(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));
	}

}
