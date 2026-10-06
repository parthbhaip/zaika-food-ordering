package com.zaika.payment;

import java.time.Clock;
import java.time.YearMonth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.common.Formatters;
import com.zaika.order.Order;
import com.zaika.order.OrderController;
import com.zaika.security.CurrentUser;

/**
 * {@code /pay/{code}} (customers only, SecurityConfig): the test-mode payment page where online checkouts land. UPI
 * and Card tabs ({@code ?tab=card}, plain links), "Pay" and "Simulate failure", and the ways out of a failed payment:
 * pay cash on delivery instead, or cancel. Paid and cash orders go on to their order page; an order cancelled before
 * it was paid (by the customer or because time ran out) shows that here. All plain forms with Post/Redirect/Get.
 */
@Controller
@RequestMapping("/pay/{code}")
class PaymentController {

	static final String VIEW = "payment/pay";

	private final PaymentService payments;

	private final Formatters fmt;

	private final Clock clock;

	PaymentController(PaymentService payments, Formatters fmt, Clock clock) {
		this.payments = payments;
		this.fmt = fmt;
		this.clock = clock;
	}

	@GetMapping
	String page(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestParam(required = false) String tab, Model model) {
		PaymentForm form = new PaymentForm();
		form.setChannel(PaymentChannel.ofTab(tab));
		return show(me, code, form, model);
	}

	@PostMapping
	String pay(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@ModelAttribute("form") PaymentForm form, BindingResult errors, Model model, RedirectAttributes redirect) {
		form.validate(errors, YearMonth.now(clock));
		if (errors.hasErrors()) {
			model.addAttribute("payError", "Check the details marked below.");
			return show(me, code, form, model);
		}
		PaymentService.PayResult result = payments.pay(me.getId(), code, form);
		Order order = result.order();
		return switch (result.kind()) {
			case PAID -> {
				redirect.addFlashAttribute(OrderController.PLACED, true);
				redirect.addFlashAttribute("flashSuccess", "Payment of " + fmt.money(order.getBill().grandTotal())
						+ " received. " + order.getRestaurant().getName() + " will confirm your order shortly.");
				yield "redirect:/orders/" + order.getCode();
			}
			case DECLINED -> "redirect:/pay/" + order.getCode() + "?tab=" + form.getChannel().getTab();
			case CLOSED -> "redirect:/pay/" + order.getCode();
		};
	}

	@PostMapping("/cash")
	String payCash(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, RedirectAttributes redirect) {
		if (!payments.switchToCash(me.getId(), code)) {
			return "redirect:/pay/" + code;
		}
		redirect.addFlashAttribute(OrderController.PLACED, true);
		redirect.addFlashAttribute("flashSuccess",
				"Order placed. Pay in cash when your food arrives. The kitchen will confirm it shortly.");
		return "redirect:/orders/" + code;
	}

	@PostMapping("/cancel")
	String cancel(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, RedirectAttributes redirect) {
		if (payments.cancel(me.getId(), code)) {
			redirect.addFlashAttribute("flashInfo", "Order cancelled. You have not been charged.");
		}
		return "redirect:/pay/" + code;
	}

	/** The payment page, or the order page once the order no longer needs paying. */
	private String show(CurrentUser me, String code, PaymentForm form, Model model) {
		PaymentService.PayPage page = payments.page(me.getId(), code);
		if (!page.awaitingPayment() && !page.cancelledUnpaid()) {
			return "redirect:/orders/" + page.order().getCode();
		}
		model.addAttribute("page", page);
		model.addAttribute("order", page.order());
		model.addAttribute("form", form);
		model.addAttribute("hideCartBar", true);
		return VIEW;
	}

}
