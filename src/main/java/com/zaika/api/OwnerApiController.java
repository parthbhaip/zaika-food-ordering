package com.zaika.api;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.zaika.common.NotFoundException;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderTransitionException;
import com.zaika.owner.MenuManagementService;
import com.zaika.owner.OwnerDashboardService;
import com.zaika.owner.OwnerOrdersService;
import com.zaika.owner.RejectOrderForm;
import com.zaika.security.CurrentUser;

/**
 * The restaurant owner's app (RESTAURANT_OWNER tokens only, {@code /api/v1/owner/**}), through the website's own
 * services: the live orders board and kitchen tickets, the kitchen's moves (accept with a preparation time, reject
 * with a reason, start preparing, mark ready), sold out / back in stock per dish, and pausing new orders. Everything
 * is the owner's own restaurant: another kitchen's order or dish is a 404, the same as an unknown one. An order that
 * moved on meanwhile (the customer cancelled, a double tap) answers 409 with its {@code status} now.
 */
@RestController
@RequestMapping("/api/v1/owner")
@Tag(name = OpenApiConfig.OWNER)
@SecurityRequirement(name = OpenApiConfig.BEARER)
class OwnerApiController {

	private static final String PREP_RULE = "Choose a preparation time: "
			+ OwnerOrdersService.PREP_CHOICES.stream().map(String::valueOf).collect(Collectors.joining(", "))
			+ " minutes.";

	private final OwnerOrdersService kitchen;

	private final MenuManagementService menus;

	private final OwnerDashboardService dashboard;

	private final Clock clock;

	OwnerApiController(OwnerOrdersService kitchen, MenuManagementService menus, OwnerDashboardService dashboard,
			Clock clock) {
		this.kitchen = kitchen;
		this.menus = menus;
		this.dashboard = dashboard;
		this.clock = clock;
	}

	/** The live board. 404 for an owner account without a restaurant. */
	@Operation(summary = "Live orders board",
			description = "New, accepted, preparing and ready orders, oldest first, with the `accepting` switch, the "
					+ "`prepChoices` for accepting and the `rejectReasons`. Poll it; `version` changes whenever the "
					+ "board does. 404 for an owner account without a restaurant.")
	@GetMapping("/orders")
	OwnerBoardResponse board(@AuthenticationPrincipal CurrentUser me) {
		return OwnerBoardResponse.of(kitchen.board(me.getId())
			.orElseThrow(() -> new NotFoundException("No restaurant for owner " + me.getId())), clock);
	}

	/** Any order that reached the kitchen (live or finished): its kitchen ticket. */
	@Operation(operationId = "kitchenTicket", summary = "Kitchen ticket",
			description = "Any order that reached the kitchen, live or finished.")
	@GetMapping("/orders/{code}")
	OwnerOrderResponse order(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		return OwnerOrderResponse.of(kitchen.ticket(me.getId(), code), LocalDateTime.now(clock), clock.getZone());
	}

	/** Accepts a new order: the customer's ETA becomes now + {@code prepMinutes} + the ride. */
	@Operation(summary = "Accept a new order",
			description = "`prepMinutes` is one of the board's `prepChoices`; the customer's ETA becomes now + prep "
					+ "time + the ride. 409 with `status` when the order moved on (say, the customer cancelled).")
	@PostMapping("/orders/{code}/accept")
	OwnerOrderResponse accept(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestBody AcceptRequest body) {
		if (body.prepMinutes() == null || !OwnerOrdersService.PREP_CHOICES.contains(body.prepMinutes())) {
			Order order = kitchen.ticket(me.getId(), code); // another kitchen's order stays a 404
			if (order.getStatus() != OrderStatus.PLACED) {
				throw movedOn(code, order.getStatus()); // the rule wins over the input
			}
			throw ApiExceptionHandler.invalidField("prepMinutes", PREP_RULE);
		}
		return move(me, code, () -> kitchen.accept(me.getId(), code, body.prepMinutes()));
	}

	/**
	 * Rejects a new order with one of the board's {@code rejectReasons} ({@code details} required for "Something
	 * else"); the customer sees it, a paid order is refunded in full.
	 */
	@Operation(summary = "Reject a new order",
			description = "With one of the board's `rejectReasons` (`details` required for \"Something else\"). The "
					+ "customer sees the reason; a paid order is refunded in full.")
	@PostMapping("/orders/{code}/reject")
	OwnerOrderResponse reject(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestBody RejectOrderForm form) throws BindException {
		BindException errors = new BindException(new BeanPropertyBindingResult(form, "reject"));
		form.validate(errors);
		if (errors.hasErrors()) {
			Order order = kitchen.ticket(me.getId(), code);
			if (order.getStatus() != OrderStatus.PLACED) {
				throw movedOn(code, order.getStatus());
			}
			throw errors;
		}
		return move(me, code, () -> kitchen.reject(me.getId(), code, form.fullReason()));
	}

	@Operation(summary = "Start preparing", description = "ACCEPTED -> PREPARING.")
	@PostMapping("/orders/{code}/preparing")
	OwnerOrderResponse preparing(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		return move(me, code, () -> kitchen.advance(me.getId(), code, OrderStatus.PREPARING));
	}

	/** Packed: the order shows up for delivery partners. */
	@Operation(summary = "Mark ready for pick-up",
			description = "PREPARING -> READY_FOR_PICKUP: the order shows up for delivery partners.")
	@PostMapping("/orders/{code}/ready")
	OwnerOrderResponse ready(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		return move(me, code, () -> kitchen.advance(me.getId(), code, OrderStatus.READY_FOR_PICKUP));
	}

	@Operation(summary = "The menu", description = "Every dish by section, with its `available` flag.")
	@GetMapping("/menu")
	OwnerMenuResponse menu(@AuthenticationPrincipal CurrentUser me) {
		return OwnerMenuResponse.of(menus.menu(me.getId()));
	}

	/** Marks a dish sold out ({@code "available": false}) or back in stock. */
	@Operation(summary = "Sold out or back in stock", description = "`available: false` marks the dish sold out.")
	@PutMapping("/menu/items/{itemId}/availability")
	OwnerMenuResponse.Dish availability(@AuthenticationPrincipal CurrentUser me, @PathVariable long itemId,
			@RequestBody AvailabilityRequest body) {
		if (body.available() == null) {
			throw ApiExceptionHandler.invalidField("available", "Use true (in stock) or false (sold out).");
		}
		return OwnerMenuResponse.Dish.of(menus.setAvailable(me.getId(), itemId, body.available()));
	}

	/**
	 * Pauses ({@code false}) or resumes new orders; orders already placed are not touched. 409 unless the restaurant
	 * is approved.
	 */
	@Operation(summary = "Pause or resume new orders",
			description = "`acceptingOrders: false` pauses new orders; orders already placed are not touched. 409 "
					+ "unless the restaurant is approved.")
	@PutMapping("/accepting-orders")
	OwnerBoardResponse.Accepting acceptingOrders(@AuthenticationPrincipal CurrentUser me,
			@RequestBody AcceptingRequest body) {
		if (body.acceptingOrders() == null) {
			throw ApiExceptionHandler.invalidField("acceptingOrders", "Use true (take new orders) or false (pause).");
		}
		return OwnerBoardResponse.Accepting.of(dashboard.setAcceptingOrders(me.getId(), body.acceptingOrders()));
	}

	/** Runs a kitchen move and answers with the order as its ticket shows it now. */
	private OwnerOrderResponse move(CurrentUser me, String code, Supplier<Order> move) {
		try {
			move.get();
		}
		catch (OrderTransitionException ex) {
			throw movedOn(code, ex.getFrom());
		}
		return order(me, code);
	}

	private static RuntimeException movedOn(String code, OrderStatus now) {
		String detail = now == OrderStatus.CANCELLED ? "The customer cancelled " + code + ". Nothing to cook for it."
				: code + " is now “" + now.getLabel() + "”, so nothing changed.";
		return ApiExceptionHandler.conflict(detail, Map.of("status", now.name()));
	}

	/** @param prepMinutes one of the board's {@code prepChoices} */
	record AcceptRequest(Integer prepMinutes) {
	}

	record AvailabilityRequest(Boolean available) {
	}

	record AcceptingRequest(Boolean acceptingOrders) {
	}

}
