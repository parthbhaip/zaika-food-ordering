package com.zaika.order;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.zaika.security.CurrentUser;

/**
 * The customer's orders (CUSTOMER area): {@code /orders}, the history 10 to a page, and {@code /orders/{code}}, one
 * order's details. Checkout and the payment page land on the detail page with the flash attribute {@code placed},
 * which turns its top into the order confirmation.
 */
@Controller
@RequestMapping("/orders")
class OrderController {

	static final String LIST_VIEW = "order/list";

	static final String DETAIL_VIEW = "order/detail";

	/** Flash attribute set by checkout and payment right after an order reaches the kitchen. */
	static final String PLACED = "placed";

	private final OrderHistoryService history;

	OrderController(OrderHistoryService history) {
		this.history = history;
	}

	@GetMapping
	String list(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String page, Model model) {
		int number = pageNumber(page);
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
		OrderHistoryService.Detail detail = history.detail(me.getId(), code);
		model.addAttribute("detail", detail);
		model.addAttribute("order", detail.order());
		return DETAIL_VIEW;
	}

	/** The 1-based page asked for: 1 when absent, 0 when unreadable. */
	private static int pageNumber(String page) {
		if (page == null) {
			return 1;
		}
		try {
			return Integer.parseInt(page);
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

}
