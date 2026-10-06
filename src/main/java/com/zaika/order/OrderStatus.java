package com.zaika.order;

import java.util.Set;

/**
 * The life of an order. Online orders start at PENDING_PAYMENT, cash orders are PLACED straight away. DELIVERED,
 * CANCELLED and REJECTED are final. Which move is allowed, and for whom, is {@link OrderWorkflow}'s business.
 */
public enum OrderStatus {

	PENDING_PAYMENT("Awaiting payment"),
	PLACED("Placed"),
	ACCEPTED("Accepted"),
	PREPARING("Preparing"),
	READY_FOR_PICKUP("Ready for pickup"),
	OUT_FOR_DELIVERY("Out for delivery"),
	DELIVERED("Delivered"),
	CANCELLED("Cancelled"),
	REJECTED("Rejected");

	/**
	 * Orders that count as "ordered on Zaika" (first-order coupons): placed and not cancelled or rejected. An online
	 * order that was never paid does not count.
	 */
	public static final Set<OrderStatus> PLACED_AND_LIVE = Set.of(PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP,
			OUT_FOR_DELIVERY, DELIVERED);

	private final String label;

	OrderStatus(String label) {
		this.label = label;
	}

	/** "Out for delivery": for chips, steppers and messages. */
	public String getLabel() {
		return label;
	}

	public boolean isFinal() {
		return this == DELIVERED || this == CANCELLED || this == REJECTED;
	}

	/** Badge colour for status chips ({@code badge-<tone>}): awaiting payment warns, live is primary, the end states. */
	public String getTone() {
		return switch (this) {
			case PENDING_PAYMENT -> "warning";
			case DELIVERED -> "success";
			case CANCELLED, REJECTED -> "danger";
			default -> "primary";
		};
	}

}
