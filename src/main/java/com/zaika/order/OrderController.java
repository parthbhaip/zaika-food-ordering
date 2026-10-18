package com.zaika.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.common.Formatters;
import com.zaika.common.Requests;
import com.zaika.review.ReviewException;
import com.zaika.review.ReviewForm;
import com.zaika.review.ReviewService;
import com.zaika.security.CurrentUser;

/**
 * The customer's orders (CUSTOMER area): {@code /orders}, the history 10 to a page, and {@code /orders/{code}}, one
 * order's details. Checkout and the payment page land on the detail page with the flash attribute {@code placed},
 * which turns its top into the order confirmation. {@code /orders/{code}/track} follows a live order: htmx polls it every
 * 10 seconds (answering 204 while nothing changed) until the order is delivered, cancelled or rejected, and the
 * customer can cancel there while the restaurant has not accepted the order yet.
 */
@Controller
@RequestMapping("/orders")
public class OrderController {

	static final String LIST_VIEW = "order/list";

	static final String DETAIL_VIEW = "order/detail";

	static final String TRACK_VIEW = "order/track";

	/** Flash attribute set by checkout and payment right after an order reaches the kitchen. */
	public static final String PLACED = "placed";

	private final OrderHistoryService history;

	private final OrderTrackingService tracking;

	private final ReviewService reviews;

	private final Formatters fmt;

	OrderController(OrderHistoryService history, OrderTrackingService tracking, ReviewService reviews,
			Formatters fmt) {
		this.history = history;
		this.tracking = tracking;
		this.reviews = reviews;
		this.fmt = fmt;
	}

	@GetMapping
	String list(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String page, Model model) {
		int number = Requests.pageNumber(page);
		if (number < 1 || "1".equals(page)) { // unreadable, out of range, or spelling out the default
			return "redirect:/orders";
		}
		Page<Order> orders = history.history(me.getId(), number - 1);
		if (number > 1 && number > orders.getTotalPages()) {
			return orders.getTotalPages() > 1 ? "redirect:/orders?page=" + orders.getTotalPages() : "redirect:/orders";
		}
		model.addAttribute("orders", orders);
		return LIST_VIEW;
	}

	@GetMapping("/{code}")
	String detail(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, Model model) {
		showDetail(history.detail(me.getId(), code), new ReviewForm(), model);
		return DETAIL_VIEW;
	}

	/**
	 * Rates a delivered order (once): PRG back to the order page, whose review card then shows the review. Validation
	 * errors re-render the page with the stars and comment kept.
	 */
	@PostMapping("/{code}/review")
	String review(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@Validated @ModelAttribute("reviewForm") ReviewForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		if (errors.hasErrors()) {
			OrderHistoryService.Detail detail = history.detail(me.getId(), code); // 404 for someone else's order
			String problem = reviews.problem(detail.order());
			if (problem == null) {
				showDetail(detail, form, model);
				return DETAIL_VIEW;
			}
			redirect.addFlashAttribute("flashError", problem); // the rule wins over the input
			return "redirect:/orders/" + code;
		}
		try {
			var review = reviews.post(me.getId(), code, form.getStars(), form.getComment());
			redirect.addFlashAttribute("flashSuccess",
					"Thanks for rating " + review.getRestaurant().getName() + "! Your review is now on its page.");
		}
		catch (ReviewException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:/orders/" + code + "#review";
	}

	private void showDetail(OrderHistoryService.Detail detail, ReviewForm form, Model model) {
		model.addAttribute("detail", detail);
		model.addAttribute("order", detail.order());
		if (detail.order().getStatus() == OrderStatus.DELIVERED) {
			model.addAttribute("review", reviews.ofOrder(detail.order().getId()).orElse(null));
			model.addAttribute("reviewForm", form);
		}
	}

	/**
	 * The tracking page. An htmx poll ({@code HX-Request}) gets only the live part, or an empty 204 when the order has
	 * not changed since the version {@code v} the page shows. An order still awaiting payment goes to its payment page.
	 */
	@GetMapping("/{code}/track")
	String track(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestParam(required = false) Long v, HttpServletRequest request, HttpServletResponse response,
			Model model) {
		OrderTrackingService.Tracking t = tracking.track(me.getId(), code);
		response.addHeader("Vary", "HX-Request");
		if (t.order().getStatus() == OrderStatus.PENDING_PAYMENT) {
			return "redirect:/pay/" + t.order().getCode();
		}
		boolean htmx = "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));
		if (htmx && t.order().getVersion().equals(v)) {
			response.setStatus(HttpStatus.NO_CONTENT.value());
			return null; // handled: htmx swaps nothing on a 204
		}
		showTracking(t, new CancelOrderForm(), false, model);
		return htmx ? TRACK_VIEW + " :: htmx-live" : TRACK_VIEW;
	}

	/** Cancels the order while it is PLACED, with the reason the customer chose (PRG back to the tracking page). */
	@PostMapping("/{code}/cancel")
	String cancel(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@ModelAttribute("cancelForm") CancelOrderForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		form.validate(errors);
		if (errors.hasErrors()) {
			OrderTrackingService.Tracking t = tracking.track(me.getId(), code);
			if (t.canCancel()) {
				showTracking(t, form, true, model);
				return TRACK_VIEW;
			}
			return cannotCancel(t.order(), redirect);
		}
		OrderTrackingService.CancelResult result = tracking.cancel(me.getId(), code, form.fullReason());
		Order order = result.order();
		if (result.cancelled()) {
			redirect.addFlashAttribute("flashSuccess", "Order cancelled. "
					+ (order.getPaymentStatus() == PaymentStatus.REFUND_PENDING
							? "Your refund of " + fmt.money(order.getBill().grandTotal())
									+ " reaches your account in 5-7 working days."
							: "You have not been charged."));
		}
		else if (order.getStatus() == OrderStatus.CANCELLED) {
			redirect.addFlashAttribute("flashInfo", "This order is already cancelled.");
		}
		else {
			return cannotCancel(order, redirect);
		}
		return "redirect:/orders/" + code + "/track";
	}

	/**
	 * Why the order cannot be cancelled here. An unpaid one is the payment page's business (pay, switch to cash or
	 * cancel there); any other has moved on past PLACED.
	 */
	private static String cannotCancel(Order order, RedirectAttributes redirect) {
		if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
			redirect.addFlashAttribute("flashError",
					"Finish or skip payment first. You can also cancel this unpaid order here.");
			return "redirect:/pay/" + order.getCode();
		}
		redirect.addFlashAttribute("flashError", "Too late to cancel: this order is now “"
				+ order.getStatus().getLabel() + "”. Need help with it? Contact us.");
		return "redirect:/orders/" + order.getCode() + "/track";
	}

	private static void showTracking(OrderTrackingService.Tracking t, CancelOrderForm form, boolean cancelOpen,
			Model model) {
		model.addAttribute("t", t);
		model.addAttribute("order", t.order());
		model.addAttribute("cancelForm", form);
		model.addAttribute("cancelReasons", CancelOrderForm.REASONS);
		model.addAttribute("cancelOpen", cancelOpen);
	}

}
