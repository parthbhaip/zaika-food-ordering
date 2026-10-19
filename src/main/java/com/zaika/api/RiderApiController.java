package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.RiderEarning;
import com.zaika.delivery.RiderEarningsService;
import com.zaika.security.CurrentUser;

/**
 * The delivery partner's app (DELIVERY_PARTNER tokens only, {@code /api/v1/rider/**}), through the website's own
 * services: go online or offline, the orders waiting for a rider, claim one, mark it picked up, deliver it with the
 * customer's 4-digit code, and earnings. A rider account without a rider profile, an unknown order or one assigned to
 * someone else is a 404. Rule refusals (not approved, offline, busy, taken by someone else, not ready yet) are 409
 * with the message the website shows; a wrong or locked delivery code is 400 with {@code errors.otp}.
 */
@RestController
@RequestMapping("/api/v1/rider")
@Tag(name = OpenApiConfig.RIDER)
@SecurityRequirement(name = OpenApiConfig.BEARER)
class RiderApiController {

	private final DeliveryService deliveries;

	private final RiderEarningsService earnings;

	private final Formatters fmt;

	private final Clock clock;

	RiderApiController(DeliveryService deliveries, RiderEarningsService earnings, Formatters fmt, Clock clock) {
		this.deliveries = deliveries;
		this.earnings = earnings;
		this.fmt = fmt;
		this.clock = clock;
	}

	@Operation(summary = "Dashboard",
			description = "Status, the online switch, the delivery on the way (`currentDelivery`, with `cashToCollect` "
					+ "for cash orders) and, while online and free, the orders waiting for a rider. Poll it; `version` "
					+ "changes whenever the waiting list does. 404 for a rider account without a rider profile.")
	@GetMapping("/dashboard")
	RiderDashboardResponse dashboard(@AuthenticationPrincipal CurrentUser me) {
		return RiderDashboardResponse.of(deliveries.dashboard(me.getId())
			.orElseThrow(() -> new NotFoundException("No rider profile for user " + me.getId())), fmt, clock.getZone());
	}

	/** The orders waiting for a rider, oldest first; empty while offline or busy with a delivery. */
	@Operation(summary = "Orders waiting for a rider",
			description = "Oldest first; empty while offline or busy with a delivery.")
	@GetMapping("/orders/available")
	List<RiderDashboardResponse.Available> available(@AuthenticationPrincipal CurrentUser me) {
		return dashboard(me).availableOrders();
	}

	/** Goes online ({@code true}, approved riders only) or offline; a delivery on its way is never dropped. */
	@Operation(summary = "Go online or offline",
			description = "Going online needs an approved rider profile (409 otherwise). Going offline never drops a "
					+ "delivery on its way.")
	@PutMapping("/online")
	RiderDashboardResponse online(@AuthenticationPrincipal CurrentUser me, @RequestBody OnlineRequest body) {
		if (body.online() == null) {
			throw ApiExceptionHandler.invalidField("online", "Use true (go online) or false (go offline).");
		}
		deliveries.setOnline(me.getId(), body.online());
		return dashboard(me);
	}

	/** Takes a waiting order: answers the dashboard with it as {@code currentDelivery}. */
	@Operation(summary = "Claim a waiting order",
			description = "One delivery at a time. 409 when another partner took it first, the kitchen has not "
					+ "accepted it, or you are offline or busy. Answers the dashboard.")
	@PostMapping("/orders/{code}/claim")
	RiderDashboardResponse claim(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		deliveries.claim(me.getId(), code);
		return dashboard(me);
	}

	/** The rider has the food (READY_FOR_PICKUP -> OUT_FOR_DELIVERY). */
	@Operation(summary = "Picked up",
			description = "READY_FOR_PICKUP -> OUT_FOR_DELIVERY. 409 while the kitchen is still cooking.")
	@PostMapping("/orders/{code}/pickup")
	RiderDashboardResponse pickUp(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		deliveries.pickUp(me.getId(), code);
		return dashboard(me);
	}

	/**
	 * Hands the order over with the customer's 4-digit code (OUT_FOR_DELIVERY -> DELIVERED; a cash order becomes paid).
	 * Five wrong codes in a row lock the code for 10 minutes.
	 */
	@Operation(summary = "Delivered",
			description = "With the 4-digit code from the customer's tracking (spaces ignored). A cash order becomes "
					+ "paid. A wrong code is a 400 in `errors.otp`; five wrong codes in a row lock it for 10 minutes.")
	@PostMapping("/orders/{code}/deliver")
	Delivered deliver(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestBody DeliverRequest body) {
		RiderEarning earning = deliveries.deliver(me.getId(), code, body.otp());
		String cash = earning.getCashCollected().signum() > 0
				? " and collected " + fmt.price(earning.getCashCollected()) + " in cash" : "";
		return new Delivered(code, at(earning.getEarnedAt(), clock.getZone()), earning.getAmount(),
				earning.getBasePayout(), earning.getFeeShare(), earning.getCashCollected(),
				code + " delivered. You earned " + fmt.price(earning.getAmount()) + cash + ".");
	}

	/** Totals and the delivery history, {@link RiderEarningsService#PAGE_SIZE} a page. */
	@Operation(summary = "Earnings",
			description = "Today, this week (from Monday), this month and cash in hand (today's cash collections), "
					+ "plus the delivery history, 20 a page.")
	@GetMapping("/earnings")
	RiderEarningsResponse earnings(@AuthenticationPrincipal CurrentUser me,
			@RequestParam(defaultValue = "1") int page) {
		if (page < 1) {
			throw ApiExceptionHandler.invalidField("page", "Pages start at 1.");
		}
		return RiderEarningsResponse.of(earnings.earnings(me.getId(), page - 1)
			.orElseThrow(() -> new NotFoundException("No rider profile for user " + me.getId())), clock.getZone());
	}

	record OnlineRequest(Boolean online) {
	}

	/** @param otp the 4-digit code on the customer's tracking page (spaces ignored) */
	record DeliverRequest(String otp) {
	}

	/**
	 * A finished delivery: {@code amount} = {@code basePayout} + {@code feeShare}; {@code cashCollected} is what the
	 * rider took at the door (zero for an order paid online); {@code message} is ready to show.
	 */
	record Delivered(String code, OffsetDateTime deliveredAt, BigDecimal amount, BigDecimal basePayout,
			BigDecimal feeShare, BigDecimal cashCollected, String message) {
	}

}
