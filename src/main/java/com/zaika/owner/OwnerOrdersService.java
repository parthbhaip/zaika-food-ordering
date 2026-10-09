package com.zaika.owner;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.common.NotFoundException;
import com.zaika.coupon.CouponService;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderTransitionException;
import com.zaika.order.OrderWorkflow;

/**
 * The owner's live orders ({@code /owner/orders}): the board of orders the kitchen still has to deal with and the
 * moves the kitchen makes on them, always through {@link OrderWorkflow}. Every lookup goes through the signed-in
 * owner's id, so another restaurant's order (or one that never reached the kitchen) is a 404. Each move locks the order row
 * first, so a customer cancelling at the same moment either wins (the move then fails with
 * {@link OrderTransitionException}) or waits.
 */
@Service
public class OwnerOrdersService {

	/** The preparation times an owner can promise when accepting an order. */
	public static final List<Integer> PREP_CHOICES = List.of(10, 15, 20, 30, 45);

	/** The statuses on the board: New (PLACED), Preparing (ACCEPTED, PREPARING) and Ready (READY_FOR_PICKUP). */
	static final Set<OrderStatus> ON_BOARD = Set.of(OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
			OrderStatus.READY_FOR_PICKUP);

	private final RestaurantRepository restaurants;

	private final OrderRepository orders;

	private final OrderWorkflow workflow;

	private final CouponService coupons;

	private final Clock clock;

	OwnerOrdersService(RestaurantRepository restaurants, OrderRepository orders, OrderWorkflow workflow,
			CouponService coupons, Clock clock) {
		this.restaurants = restaurants;
		this.orders = orders;
		this.workflow = workflow;
		this.coupons = coupons;
		this.clock = clock;
	}

	/** The board of the owner's restaurant (empty if this owner has none). Two queries whatever the volume. */
	@Transactional(readOnly = true)
	public Optional<Board> board(long ownerId) {
		return restaurants.findByOwnerId(ownerId)
			.map(r -> new Board(r, orders.findByRestaurantIdAndStatusInOrderByPlacedAtAscIdAsc(r.getId(), ON_BOARD),
					LocalDateTime.now(clock)));
	}

	/** One of the restaurant's placed orders, for its kitchen ticket. */
	@Transactional(readOnly = true)
	public Order ticket(long ownerId, String code) {
		return orders.findTicketByCodeAndRestaurantOwnerId(code, ownerId)
			.filter(OwnerOrdersService::placed)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for owner " + ownerId));
	}

	/**
	 * Accepts a new order with the kitchen's preparation time: the customer's ETA becomes now + {@code prepMinutes} +
	 * the ride.
	 * @throws IllegalArgumentException if {@code prepMinutes} is not one of {@link #PREP_CHOICES}
	 */
	@Transactional
	public Order accept(long ownerId, String code, int prepMinutes) {
		if (!PREP_CHOICES.contains(prepMinutes)) {
			throw new IllegalArgumentException("Preparation time not offered: " + prepMinutes);
		}
		Order order = workflow.transition(own(ownerId, code), OrderStatus.ACCEPTED, OrderActor.restaurant(ownerId));
		order.setEstimatedDeliveryAt(LocalDateTime.now(clock).plusMinutes(prepMinutes + Restaurant.RIDE_MINUTES));
		return order;
	}

	/** Rejects a new order with {@code reason}: the coupon use is given back and a paid order goes to refund. */
	@Transactional
	public Order reject(long ownerId, String code, String reason) {
		Order order = workflow.transition(own(ownerId, code), OrderStatus.REJECTED, OrderActor.restaurant(ownerId),
				reason);
		coupons.release(order);
		return order;
	}

	/** An accepted order goes on the stove (PREPARING), or a prepared one is packed (READY_FOR_PICKUP). */
	@Transactional
	public Order advance(long ownerId, String code, OrderStatus target) {
		if (target != OrderStatus.PREPARING && target != OrderStatus.READY_FOR_PICKUP) {
			throw new IllegalArgumentException("Not a kitchen step: " + target);
		}
		return workflow.transition(own(ownerId, code), target, OrderActor.restaurant(ownerId));
	}

	/** The order, row-locked, if it belongs to the owner's restaurant and has been placed. */
	private Order own(long ownerId, String code) {
		return orders.findForUpdateByCode(code)
			.filter(o -> o.getRestaurant().getOwner().getId() == ownerId)
			.filter(OwnerOrdersService::placed)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for owner " + ownerId));
	}

	/** Reached the kitchen at some point: not awaiting payment, nor cancelled or expired before it was ever paid. */
	private static boolean placed(Order order) {
		return order.timestampOf(OrderStatus.PLACED) != null;
	}

	/** The default in the accept form: the listed choice closest to the restaurant's usual preparation time. */
	static int defaultPrep(int usualMinutes) {
		int best = PREP_CHOICES.get(0);
		for (int choice : PREP_CHOICES) {
			if (Math.abs(choice - usualMinutes) < Math.abs(best - usualMinutes)) {
				best = choice;
			}
		}
		return best;
	}

	/**
	 * The board: the restaurant, its live kitchen orders (customer, rider and dishes loaded, oldest first) and the time
	 * it was read. {@link #version()} changes whenever anything on it does, so a poll can answer "nothing new".
	 */
	public record Board(Restaurant restaurant, List<Order> orders, LocalDateTime now) {

		/** Waiting for the kitchen to accept or reject. */
		public List<Order> fresh() {
			return in(Set.of(OrderStatus.PLACED));
		}

		/** Accepted (not started yet) or on the stove. */
		public List<Order> cooking() {
			return in(Set.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING));
		}

		/** Packed, waiting for the rider. */
		public List<Order> ready() {
			return in(Set.of(OrderStatus.READY_FOR_PICKUP));
		}

		public boolean empty() {
			return orders.isEmpty();
		}

		/** The accept form's preselected preparation time. */
		public int defaultPrep() {
			return OwnerOrdersService.defaultPrep(restaurant.getAvgPrepMinutes());
		}

		/** When the food should be packed: the customer's ETA minus the ride. */
		public LocalDateTime readyBy(Order order) {
			return order.getEstimatedDeliveryAt().minusMinutes(Restaurant.RIDE_MINUTES);
		}

		/** "Riya": the customer's first name, all the kitchen needs on the bag. */
		public String firstName(Order order) {
			return order.getCustomer().firstName();
		}

		/** Accepted or cooking and past its ready-by time. */
		public boolean late(Order order) {
			return order.getStatus() != OrderStatus.READY_FOR_PICKUP && order.getStatus() != OrderStatus.PLACED
					&& now.isAfter(readyBy(order));
		}

		/**
		 * A fingerprint of every order (id, version and whether it is running late, which changes with the clock alone)
		 * and the accepting switch, for the 10-second poll.
		 */
		public String version() {
			StringBuilder all = new StringBuilder().append(restaurant.isAcceptingOrders());
			orders.forEach(o -> all.append('|').append(o.getId()).append(':').append(o.getVersion())
				.append(late(o) ? "L" : ""));
			CRC32 crc = new CRC32();
			crc.update(all.toString().getBytes(StandardCharsets.US_ASCII));
			return Long.toString(crc.getValue(), 36) + "-" + orders.size();
		}

		private List<Order> in(Set<OrderStatus> statuses) {
			return orders.stream().filter(o -> statuses.contains(o.getStatus())).toList();
		}

	}

}
