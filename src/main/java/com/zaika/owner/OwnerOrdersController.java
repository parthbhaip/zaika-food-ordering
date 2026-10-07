package com.zaika.owner;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
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

import com.zaika.catalog.Restaurant;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderTransitionException;
import com.zaika.order.PaymentStatus;
import com.zaika.owner.OwnerOrdersService.Board;
import com.zaika.security.CurrentUser;

/**
 * The owner's live orders board ({@code /owner/orders}) and kitchen tickets ({@code /owner/orders/{code}}). htmx polls
 * the board every 10 seconds with the version it shows ({@code ?v=}) and gets an empty 204 until something changed.
 * Accept, Mark preparing and Mark ready are plain forms (Post/Redirect/Get with a toast); with htmx they swap the
 * board and add the toast out of band. Reject is a plain form only, so a missing reason re-renders the page with the
 * error next to that order's form.
 */
@Controller
@RequestMapping("/owner/orders")
class OwnerOrdersController {

	static final String VIEW = "owner/orders";

	static final String TICKET_VIEW = "owner/ticket";

	private final OwnerOrdersService service;

	private final Formatters fmt;

	OwnerOrdersController(OwnerOrdersService service, Formatters fmt) {
		this.service = service;
		this.fmt = fmt;
	}

	@GetMapping
	String board(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String v,
			HttpServletRequest request, HttpServletResponse response, Model model) {
		response.addHeader("Vary", "HX-Request");
		Board board = service.board(me.getId()).orElse(null);
		boolean htmx = htmx(request);
		if (htmx && board != null && board.version().equals(v)) {
			response.setStatus(HttpStatus.NO_CONTENT.value());
			return null; // handled: htmx swaps nothing on a 204
		}
		show(board, new RejectOrderForm(), null, model);
		return htmx && board != null ? VIEW + " :: board-poll" : VIEW;
	}

	@GetMapping("/{code}")
	String ticket(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, Model model) {
		Order order = service.ticket(me.getId(), code);
		model.addAttribute("order", order);
		model.addAttribute("readyBy", order.getEstimatedDeliveryAt().minusMinutes(
				Restaurant.RIDE_MINUTES));
		return TICKET_VIEW;
	}

	@PostMapping("/{code}/accept")
	String accept(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestParam(required = false) Integer prepMinutes, HttpServletRequest request, Model model,
			RedirectAttributes redirect) {
		if (prepMinutes == null || !OwnerOrdersService.PREP_CHOICES.contains(prepMinutes)) {
			service.ticket(me.getId(), code); // another kitchen's order stays a 404
			return answer(me, code, "error", "Choose a preparation time from the list.", request, model, redirect);
		}
		return act(me, code, request, model, redirect, () -> {
			Order order = service.accept(me.getId(), code, prepMinutes);
			return "Accepted " + code + ". Ready by " + fmt.time(order.getEstimatedDeliveryAt()
				.minusMinutes(Restaurant.RIDE_MINUTES)) + ", the customer's ETA is updated.";
		});
	}

	@PostMapping("/{code}/preparing")
	String preparing(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, HttpServletRequest request,
			Model model, RedirectAttributes redirect) {
		return act(me, code, request, model, redirect, () -> {
			service.advance(me.getId(), code, OrderStatus.PREPARING);
			return code + " is being prepared.";
		});
	}

	@PostMapping("/{code}/ready")
	String ready(@AuthenticationPrincipal CurrentUser me, @PathVariable String code, HttpServletRequest request,
			Model model, RedirectAttributes redirect) {
		return act(me, code, request, model, redirect, () -> {
			service.advance(me.getId(), code, OrderStatus.READY_FOR_PICKUP);
			return code + " is ready for pickup. A delivery partner will collect it.";
		});
	}

	@PostMapping("/{code}/reject")
	String reject(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@ModelAttribute("rejectForm") RejectOrderForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		form.validate(errors);
		if (errors.hasErrors()) {
			Board board = service.board(me.getId()).orElse(null);
			if (board != null && board.fresh().stream().anyMatch(o -> o.getCode().equals(code))) {
				show(board, form, code, model);
				return VIEW;
			}
			// moved on meanwhile (someone else's order is a 404 here, not a hint that it exists)
			redirect.addFlashAttribute("flashError", movedOn(code, service.ticket(me.getId(), code).getStatus()));
			return "redirect:/owner/orders";
		}
		try {
			Order order = service.reject(me.getId(), code, form.fullReason());
			redirect.addFlashAttribute("flashSuccess", "Rejected " + code + ". "
					+ (order.getPaymentStatus() == PaymentStatus.REFUND_PENDING
							? "The customer gets a full refund and sees your reason."
							: "The customer sees your reason on their order."));
		}
		catch (OrderTransitionException ex) {
			redirect.addFlashAttribute("flashError", movedOn(code, ex.getFrom()));
		}
		return "redirect:/owner/orders";
	}

	/** Runs a kitchen move, then answers with a toast: PRG to the board, or for htmx the board plus the toast. */
	private String act(CurrentUser me, String code, HttpServletRequest request, Model model,
			RedirectAttributes redirect, Supplier<String> move) {
		try {
			return answer(me, code, "success", move.get(), request, model, redirect);
		}
		catch (OrderTransitionException ex) {
			return answer(me, code, "error", movedOn(code, ex.getFrom()), request, model, redirect);
		}
	}

	private String answer(CurrentUser me, String code, String kind, String message, HttpServletRequest request,
			Model model, RedirectAttributes redirect) {
		if (!htmx(request)) {
			redirect.addFlashAttribute("error".equals(kind) ? "flashError" : "flashSuccess", message);
			return "redirect:/owner/orders#order-" + code;
		}
		Board board = service.board(me.getId())
			.orElseThrow(() -> new NotFoundException("No restaurant for owner " + me.getId()));
		show(board, new RejectOrderForm(), null, model);
		model.addAttribute("toastKind", kind);
		model.addAttribute("toastMessage", message);
		return VIEW + " :: board-update";
	}

	/** Why a move did nothing: the order is no longer where the board showed it. */
	private static String movedOn(String code, OrderStatus now) {
		if (now == OrderStatus.CANCELLED) {
			return "The customer cancelled " + code + ". Nothing to cook for it.";
		}
		return code + " is now “" + now.getLabel() + "”, so nothing changed.";
	}

	private static void show(Board board, RejectOrderForm form, String rejecting, Model model) {
		model.addAttribute("board", board);
		model.addAttribute("rejectForm", form);
		model.addAttribute("rejecting", rejecting);
		model.addAttribute("prepChoices", OwnerOrdersService.PREP_CHOICES);
		model.addAttribute("rejectReasons", RejectOrderForm.REASONS);
	}

	private static boolean htmx(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"))
				&& !"true".equals(request.getHeader("HX-History-Restore-Request"));
	}

}
