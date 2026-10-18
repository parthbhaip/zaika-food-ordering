package com.zaika.order;

/**
 * Published by {@link OrderWorkflow} inside the transaction of every status change: {@code order} moved from
 * {@code from} to {@code to} on behalf of {@code actor}. {@code to} is recorded because a listener that runs later in
 * the transaction (e.g. just before commit) may see the order already moved on.
 */
public record OrderStatusChanged(Order order, OrderStatus from, OrderStatus to, OrderActor actor) {
}
