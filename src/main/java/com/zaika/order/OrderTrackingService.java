package com.zaika.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;
import com.zaika.coupon.CouponService;
import com.zaika.user.User;

/**
 * Live tracking of one of the customer's orders ({@code /orders/{code}/track}): where it stands as a step list, the
 * ETA, the rider once one is assigned, and the delivery code while it is out for delivery. The customer may cancel
 * while the order is PLACED (the restaurant has not accepted it yet); that gives the coupon use back and a paid order
 * goes to refund. Anyone else's code is a 404.
 */
@Service
public class OrderTrackingService {

	/** The happy path, in order. */
	static final List<OrderStatus> JOURNEY = List.of(OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
			OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);

	private final OrderRepository orders;

	private final OrderWorkflow workflow;

	private final CouponService coupons;

	private final Clock clock;

	OrderTrackingService(OrderRepository orders, OrderWorkflow workflow, CouponService coupons, Clock clock) {
		this.orders = orders;
		this.workflow = workflow;
		this.coupons = coupons;
		this.clock = clock;
	}

	/** The customer's order with its restaurant, rider and dishes, as of now. */
	@Transactional(readOnly = true)
	public Tracking track(long customerId, String code) {
		Order order = orders.findTrackedByCodeAndCustomerId(code, customerId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for customer " + customerId));
		return new Tracking(order, LocalDateTime.now(clock));
	}

	/**
	 * The customer cancels their order with {@code reason}, only while it is PLACED. Locks the order row first, so a
	 * restaurant accepting it at the same moment either wins (then this returns {@code cancelled = false}) or waits.
	 */
	@Transactional
	public CancelResult cancel(long customerId, String code, String reason) {
		Order order = orders.findForUpdateByCode(code)
			.filter(o -> o.getCustomer().getId() == customerId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for customer " + customerId));
		if (order.getStatus() != OrderStatus.PLACED) {
			return new CancelResult(order, false);
		}
		workflow.transition(order, OrderStatus.CANCELLED, OrderActor.customer(customerId), reason);
		coupons.release(order);
		return new CancelResult(order, true);
	}

	/** What a cancel did: {@code cancelled} is false when the order was no longer PLACED (nothing changed). */
	public record CancelResult(Order order, boolean cancelled) {
	}

	/** The tracking page's data: the order (restaurant, rider and dishes loaded) and the time it was read. */
	public record Tracking(Order order, LocalDateTime now) {

		/** Still moving: the page polls for changes. */
		public boolean live() {
			return order.getStatus() != OrderStatus.PENDING_PAYMENT && !order.getStatus().isFinal();
		}

		/** The customer may still cancel: the restaurant has not accepted the order yet. */
		public boolean canCancel() {
			return order.getStatus() == OrderStatus.PLACED;
		}

		/** The 4-digit code for the rider shows only while the order is on its way. */
		public boolean showOtp() {
			return order.getStatus() == OrderStatus.OUT_FOR_DELIVERY;
		}

		/** Live and past its estimated delivery time. */
		public boolean late() {
			return live() && now.isAfter(order.getEstimatedDeliveryAt());
		}

		/** The delivery code digit by digit ("4 8 2 1"), so screen readers do not read it as a number. */
		public String spokenOtp() {
			return String.join(" ", order.getDeliveryOtp().split(""));
		}

		/** The big line at the top. */
		public String headline() {
			return switch (order.getStatus()) {
				case PENDING_PAYMENT -> "Waiting for your payment";
				case PLACED -> "Waiting for the restaurant to accept";
				case ACCEPTED -> "Order accepted";
				case PREPARING -> "Your food is being prepared";
				case READY_FOR_PICKUP -> "Packed and ready for pickup";
				case OUT_FOR_DELIVERY -> "On the way to you";
				case DELIVERED -> "Delivered. Enjoy your meal!";
				case CANCELLED -> "Order cancelled";
				case REJECTED -> "The restaurant could not take this order";
			};
		}

		/** Who ended a cancelled or rejected order, as a sentence; null otherwise. */
		public String endedBy() {
			return new OrderHistoryService.Detail(order, null, now).endedBy();
		}

		/** The rider's first name, or null before one is assigned. */
		public String riderFirstName() {
			User rider = order.getRider();
			return rider == null ? null : rider.getName().strip().split("\\s+")[0];
		}

		/**
		 * The progress list. A moving or delivered order shows the whole journey (done, current, still to come); a
		 * cancelled or rejected one shows the steps it reached and then how it ended, with the reason.
		 */
		public List<Step> steps() {
			OrderStatus status = order.getStatus();
			boolean ended = status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED;
			int current = JOURNEY.indexOf(status);
			List<Step> steps = new ArrayList<>();
			for (int i = 0; i < JOURNEY.size(); i++) {
				OrderStatus step = JOURNEY.get(i);
				LocalDateTime at = order.timestampOf(step);
				if (ended) {
					if (at != null) {
						steps.add(new Step(label(step), null, at, Step.State.DONE, "check"));
					}
					continue;
				}
				Step.State state = i < current || (i == current && step == OrderStatus.DELIVERED) ? Step.State.DONE
						: i == current ? Step.State.CURRENT : Step.State.UPCOMING;
				steps.add(switch (state) {
					case DONE -> new Step(label(step), null, at, state, "check");
					case CURRENT -> new Step(label(step), note(step), at, state, icon(step));
					default -> new Step(label(step), null, null, state, icon(step));
				});
			}
			if (ended) {
				steps.add(new Step(status == OrderStatus.REJECTED ? "Rejected by the restaurant" : "Cancelled",
						order.getCancelReason(), order.timestampOf(status), Step.State.FAILED, "x"));
			}
			return steps;
		}

		private static String label(OrderStatus status) {
			return switch (status) {
				case PLACED -> "Order placed";
				case ACCEPTED -> "Accepted by the restaurant";
				case PREPARING -> "Being prepared";
				default -> status.getLabel();
			};
		}

		private String note(OrderStatus status) {
			String rider = riderFirstName();
			return switch (status) {
				case PLACED -> "Waiting for " + order.getRestaurant().getName() + " to accept";
				case ACCEPTED -> "The kitchen starts cooking shortly";
				case PREPARING -> "Cooked fresh for you";
				case READY_FOR_PICKUP -> rider == null ? "Waiting for a delivery partner" : rider + " is picking it up";
				case OUT_FOR_DELIVERY -> rider == null ? "Heading your way" : rider + " is heading your way";
				default -> null;
			};
		}

		private static String icon(OrderStatus status) {
			return switch (status) {
				case PLACED -> "receipt";
				case ACCEPTED, READY_FOR_PICKUP -> "store";
				case PREPARING -> "clock";
				case OUT_FOR_DELIVERY -> "bike";
				default -> "location";
			};
		}

	}

	/** One line of the progress list. {@code at} is null for steps still to come; {@code note} is optional. */
	public record Step(String label, String note, LocalDateTime at, State state, String icon) {

		public enum State {
			DONE, CURRENT, UPCOMING, FAILED
		}

		/** The stepper's class: {@code is-done}, {@code is-current}, {@code is-failed}, or none. */
		public String cssClass() {
			return switch (state) {
				case DONE -> "is-done";
				case CURRENT -> "is-current";
				case FAILED -> "is-failed";
				case UPCOMING -> "";
			};
		}

	}

}
