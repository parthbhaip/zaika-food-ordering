package com.zaika.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.admin.OrderAdminService.Detail;
import com.zaika.common.Formatters;
import com.zaika.common.Requests;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentStatus;
import com.zaika.security.CurrentUser;

/**
 * Orders for the admin ({@code /admin/orders}): the filtered list (canonical URLs), one order with its full timeline
 * ({@code /admin/orders/{code}}) and the admin cancel with a reason, plain Post/Redirect/Get. A missing reason
 * re-renders the order page with the cancel form open and the error inline.
 */
@Controller
class OrderAdminController {

	static final String LIST_VIEW = "admin/orders";

	static final String DETAIL_VIEW = "admin/order";

	private final OrderAdminService admin;

	private final Formatters fmt;

	OrderAdminController(OrderAdminService admin, Formatters fmt) {
		this.admin = admin;
		this.fmt = fmt;
	}

	@GetMapping(OrderAdminFilter.BASE)
	String list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) String restaurant, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String page,
			HttpServletRequest request, Model model) {
		var restaurants = admin.restaurants();
		OrderAdminFilter filter = OrderAdminFilter.of(q, status, restaurant, from, to, page, restaurants, admin.today());
		Page<Order> orders = admin.list(filter);
		filter.setPage(Math.min(filter.getPage(), Math.max(orders.getTotalPages(), 1)));
		if (!filter.url().equals(Requests.currentUrl(request))) {
			return "redirect:" + filter.url();
		}
		model.addAttribute("filter", filter);
		model.addAttribute("orders", orders);
		model.addAttribute("restaurants", restaurants);
		model.addAttribute("statuses", OrderStatus.values());
		return LIST_VIEW;
	}

	@GetMapping(OrderAdminFilter.BASE + "/{code}")
	String detail(@PathVariable String code, @AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("d", admin.detail(code, me.getId()));
		model.addAttribute("cancelForm", new AdminCancelForm());
		model.addAttribute("cancelOpen", false);
		return DETAIL_VIEW;
	}

	@PostMapping(OrderAdminFilter.BASE + "/{code}/cancel")
	String cancel(@PathVariable String code, @Valid @ModelAttribute("cancelForm") AdminCancelForm form,
			BindingResult errors, @AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (errors.hasErrors()) {
			Detail d = admin.detail(code, me.getId());
			if (d.canCancel()) {
				model.addAttribute("d", d);
				model.addAttribute("cancelOpen", true);
				return DETAIL_VIEW;
			}
			redirect.addFlashAttribute("flashError", OrderAdminService.refusal(d.order()));
			return "redirect:" + OrderAdminFilter.BASE + "/" + d.order().getCode();
		}
		try {
			Order order = admin.cancel(code, me.getId(), form.getReason());
			redirect.addFlashAttribute("flashSuccess", "Order " + order.getCode() + " is cancelled. "
					+ (order.getPaymentStatus() == PaymentStatus.REFUND_PENDING
							? "Its refund of " + fmt.money(order.getBill().grandTotal()) + " is now pending."
							: "Nothing was charged, so there is nothing to refund."));
		}
		catch (ModerationException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:" + OrderAdminFilter.BASE + "/" + code;
	}

}
