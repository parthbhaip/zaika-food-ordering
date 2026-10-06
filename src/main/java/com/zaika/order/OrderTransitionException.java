package com.zaika.order;

/**
 * A status change the workflow does not allow from the order's current status (for example accepting an order that
 * was just cancelled). Usually two people acting at once: callers show a "this order has moved on" message and
 * reload. The message is for logs.
 */
public class OrderTransitionException extends RuntimeException {

	private final OrderStatus from;

	private final OrderStatus to;

	public OrderTransitionException(String code, OrderStatus from, OrderStatus to) {
		super("Order " + code + " cannot go from " + from + " to " + to);
		this.from = from;
		this.to = to;
	}

	public OrderStatus getFrom() {
		return from;
	}

	public OrderStatus getTo() {
		return to;
	}

}
