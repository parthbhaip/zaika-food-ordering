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

import com.zaika.common.Formatters;
import com.zaika.delivery.DeliveryRuleException;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.DeliveryService.Dashboard;
import com.zaika.delivery.OtpException;
import com.zaika.delivery.RiderEarning;
import com.zaika.order.Order;
import com.zaika.security.CurrentUser;

/**
 * The rider's home ({@code /partner}): review status, the online switch, the current delivery and the orders waiting
 * for a rider. The switch, Claim, Picked up and Delivered are plain forms (Post/Redirect/Get with a toast; a refused
 * delivery code re-renders the page with the error on the code field); the list of waiting orders and a delivery the
 * kitchen is still cooking poll with htmx (see {@link #dashboard}).
 */
@Controller
class PartnerDashboardController {

	private final DeliveryService deliveries;

	private final Formatters fmt;

	PartnerDashboardController(DeliveryService deliveries, Formatters fmt) {
		this.deliveries = deliveries;
		this.fmt = fmt;
	}

	/**
	 * The page, or for the htmx poll ({@code ?v=} the version the list shows): 204 while the waiting orders are
	 * unchanged, the {@code available} fragment when they changed, and {@code HX-Refresh} when the list no longer
	 * belongs on the page (offline, suspended, or a delivery claimed in another tab), so the page never goes stale. The
	 * current delivery's poll ({@code ?delivery=} its {@link Dashboard#deliveryVersion()}) gets 204 while it is
	 * unchanged and {@code HX-Refresh} once it moved (the kitchen marked it ready, it was cancelled).
	 */
	@GetMapping("/partner")
	String dashboard(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String v,
			@RequestParam(required = false) String delivery, HttpServletRequest request,
			HttpServletResponse response, Model model) {
		response.addHeader("Vary", "HX-Request");
		Dashboard d = deliveries.dashboard(me.getId()).orElse(null);
		if (htmx(request) && delivery != null) {
			if (d == null || !delivery.equals(d.deliveryVersion())) {
				response.setHeader("HX-Refresh", "true");
			}
			response.setStatus(HttpStatus.NO_CONTENT.value());
			return null;
		}
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

	@PostMapping("/partner/orders/{code}/pickup")
	String pickUp(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, RedirectAttributes redirect) {
		try {
			deliveries.pickUp(me.getId(), code);
			redirect.addFlashAttribute("flashSuccess",
					code + " picked up. Ask the customer for their 4-digit delivery code at the door.");
		}
		catch (DeliveryRuleException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:/partner#current-delivery";
	}

	/** A refused code shows the page again with the message on the code field (the typed code kept). */
	@PostMapping("/partner/orders/{code}/deliver")
	String deliver(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestParam(required = false) String otp, RedirectAttributes redirect, Model model) {
		try {
			RiderEarning earning = deliveries.deliver(me.getId(), code, otp);
			String cash = earning.getCashCollected().signum() > 0
					? " and collected " + fmt.price(earning.getCashCollected()) + " in cash" : "";
			redirect.addFlashAttribute("flashSuccess",
					code + " delivered. You earned " + fmt.price(earning.getAmount()) + cash + ".");
			return "redirect:/partner";
		}
		catch (OtpException ex) {
			model.addAttribute("d", deliveries.dashboard(me.getId()).orElse(null));
			model.addAttribute("otpError", ex.getMessage());
			model.addAttribute("otp", otp);
			return "partner/dashboard";
		}
		catch (DeliveryRuleException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
			return "redirect:/partner#current-delivery";
		}
	}

	private static boolean htmx(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));
	}

}
