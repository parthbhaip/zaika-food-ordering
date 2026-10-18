package com.zaika.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.zaika.common.ForbiddenException;
import com.zaika.order.OrderActor.Kind;

/**
 * The one place that changes an order's status. It knows which moves exist, who may make each one, and stamps the
 * new status's timestamp (from the app clock):
 *
 * <pre>
 * PENDING_PAYMENT  -> PLACED            system (payment confirmed, or cash on delivery chosen)
 * PENDING_PAYMENT  -> CANCELLED         customer, admin, system (unpaid for 15 minutes)
 * PLACED           -> ACCEPTED          restaurant
 * PLACED           -> REJECTED          restaurant (reason)
 * PLACED           -> CANCELLED         customer (reason), admin (reason)
 * ACCEPTED         -> PREPARING         restaurant
 * PREPARING        -> READY_FOR_PICKUP  restaurant
 * READY_FOR_PICKUP -> OUT_FOR_DELIVERY  the assigned rider
 * OUT_FOR_DELIVERY -> DELIVERED         the assigned rider (the caller checks the OTP first)
 * ACCEPTED, PREPARING, READY_FOR_PICKUP, OUT_FOR_DELIVERY -> CANCELLED   admin (reason)
 * </pre>
 *
 * A customer acts only on their own orders, a restaurant owner only on their restaurant's, a rider only on orders
 * assigned to them; anyone else gets {@link ForbiddenException}, as does a role that never makes that move. A role
 * that does make the move, but not from the order's current status (the order moved on meanwhile), gets
 * {@link OrderTransitionException}. Cancelling or rejecting needs a reason and records who did it; a paid order
 * that is cancelled or rejected goes to REFUND_PENDING, and a cash order is PAID once delivered. Every move publishes
 * an {@link OrderStatusChanged} event (notifications and mails listen to it).
 */
@Component
public class OrderWorkflow {

	private static final Map<OrderStatus, Map<OrderStatus, Set<Kind>>> MOVES = moves();

	private final Clock clock;

	private final ApplicationEventPublisher events;

	@Autowired
	public OrderWorkflow(Clock clock, ApplicationEventPublisher events) {
		this.clock = clock;
		this.events = events;
	}

	/** A workflow nobody listens to (unit tests). */
	public OrderWorkflow(Clock clock) {
		this(clock, event -> {
		});
	}

	/** {@link #transition(Order, OrderStatus, OrderActor, String)} for moves that need no reason. */
	public Order transition(Order order, OrderStatus target, OrderActor actor) {
		return transition(order, target, actor, null);
	}

	/**
	 * Moves {@code order} to {@code target} on behalf of {@code actor}.
	 *
	 * @param reason why, required (non-blank, at most {@link Order#MAX_REASON} characters) for CANCELLED and
	 *               REJECTED, ignored otherwise
	 * @throws ForbiddenException       if the actor is not a party to this order, or their role never makes this move
	 * @throws OrderTransitionException if their role makes this move, but not from the order's current status
	 * @throws IllegalArgumentException if a needed reason is missing or too long
	 */
	public Order transition(Order order, OrderStatus target, OrderActor actor, String reason) {
		Objects.requireNonNull(target, "target");
		if (!isParty(order, actor)) {
			throw new ForbiddenException(actor + " is not a party to order " + order.getCode());
		}
		if (!allowed(order.getStatus(), target, actor.kind())) {
			if (MOVES.values().stream().noneMatch(to -> to.getOrDefault(target, Set.of()).contains(actor.kind()))) {
				throw new ForbiddenException(actor.kind() + " never moves an order to " + target);
			}
			throw new OrderTransitionException(order.getCode(), order.getStatus(), target);
		}
		boolean ending = target == OrderStatus.CANCELLED || target == OrderStatus.REJECTED;
		String why = ending ? reason(reason) : null;

		OrderStatus from = order.getStatus();
		order.moveTo(target, LocalDateTime.now(clock));
		if (ending) {
			order.recordCancellation(why, actor.kind());
			if (order.getPaymentStatus() == PaymentStatus.PAID) {
				order.setPaymentStatus(PaymentStatus.REFUND_PENDING);
			}
		}
		if (target == OrderStatus.DELIVERED && order.getPaymentMethod() == PaymentMethod.COD) {
			order.setPaymentStatus(PaymentStatus.PAID);
		}
		events.publishEvent(new OrderStatusChanged(order, from, target, actor));
		return order;
	}

	/**
	 * The online payment for {@code order} went through: it goes to the kitchen (PLACED, by the system) and is PAID.
	 *
	 * @throws OrderTransitionException if the order is no longer awaiting payment
	 * @throws IllegalStateException    if it is a cash order
	 */
	public Order confirmPayment(Order order) {
		if (order.getPaymentMethod() != PaymentMethod.ONLINE) {
			throw new IllegalStateException("Order " + order.getCode() + " is not paid online");
		}
		transition(order, OrderStatus.PLACED, OrderActor.system());
		order.setPaymentStatus(PaymentStatus.PAID);
		return order;
	}

	/** Whether {@code actor} may move {@code order} to {@code target} right now (to show or hide a button). */
	public boolean can(Order order, OrderStatus target, OrderActor actor) {
		return isParty(order, actor) && allowed(order.getStatus(), target, actor.kind());
	}

	private static boolean allowed(OrderStatus from, OrderStatus to, Kind kind) {
		return MOVES.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).contains(kind);
	}

	private static boolean isParty(Order order, OrderActor actor) {
		return switch (actor.kind()) {
			case SYSTEM, ADMIN -> true;
			case CUSTOMER -> actor.userId().equals(order.getCustomer().getId());
			case RESTAURANT -> actor.userId().equals(order.getRestaurant().getOwner().getId());
			case RIDER -> order.getRider() != null && actor.userId().equals(order.getRider().getId());
		};
	}

	private static String reason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A reason is required to cancel or reject an order");
		}
		String stripped = reason.strip();
		if (stripped.length() > Order.MAX_REASON) {
			throw new IllegalArgumentException("Reason longer than " + Order.MAX_REASON);
		}
		return stripped;
	}

	private static Map<OrderStatus, Map<OrderStatus, Set<Kind>>> moves() {
		Map<OrderStatus, Map<OrderStatus, Set<Kind>>> moves = new EnumMap<>(OrderStatus.class);
		move(moves, OrderStatus.PENDING_PAYMENT, OrderStatus.PLACED, Kind.SYSTEM);
		move(moves, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, Kind.CUSTOMER, Kind.ADMIN, Kind.SYSTEM);
		move(moves, OrderStatus.PLACED, OrderStatus.ACCEPTED, Kind.RESTAURANT);
		move(moves, OrderStatus.PLACED, OrderStatus.REJECTED, Kind.RESTAURANT);
		move(moves, OrderStatus.PLACED, OrderStatus.CANCELLED, Kind.CUSTOMER, Kind.ADMIN);
		move(moves, OrderStatus.ACCEPTED, OrderStatus.PREPARING, Kind.RESTAURANT);
		move(moves, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, Kind.RESTAURANT);
		move(moves, OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY, Kind.RIDER);
		move(moves, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, Kind.RIDER);
		for (OrderStatus from : EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP,
				OrderStatus.OUT_FOR_DELIVERY)) {
			move(moves, from, OrderStatus.CANCELLED, Kind.ADMIN);
		}
		return Collections.unmodifiableMap(moves);
	}

	private static void move(Map<OrderStatus, Map<OrderStatus, Set<Kind>>> moves, OrderStatus from, OrderStatus to,
			Kind... kinds) {
		moves.computeIfAbsent(from, f -> new EnumMap<>(OrderStatus.class)).put(to, Set.of(kinds));
	}

}
