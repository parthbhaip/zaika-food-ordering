package com.zaika.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.cart.PricingService;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.order.DeliveryAddress;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.user.AddressLabel;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Builds committed orders for tests and walks them to a status through the real {@link OrderWorkflow}:
 * <pre>orders.place(customerId, dish.getId(), 2, PaymentMethod.COD, OrderStatus.DELIVERED);</pre>
 * Priced by {@link PricingService} with no discount, delivered to a fixed Rajarampuri address. Reaching
 * OUT_FOR_DELIVERY or DELIVERED assigns a fresh rider account. Use a test database of your own.
 */
@Component
public class OrderFixtures {

	public static final DeliveryAddress ADDRESS = new DeliveryAddress(AddressLabel.HOME, "Flat 4B, Shree Apartments",
			"Rajarampuri 5th Lane", "Ganesh Mandir", "Kolhapur", "416008");

	private final OrderRepository orders;

	private final OrderWorkflow workflow;

	private final UserRepository users;

	private final MenuItemRepository items;

	private final PricingService pricing;

	private final TransactionTemplate tx;

	private final Clock clock;

	public OrderFixtures(OrderRepository orders, OrderWorkflow workflow, UserRepository users,
			MenuItemRepository items, PricingService pricing, TransactionTemplate tx, Clock clock) {
		this.orders = orders;
		this.workflow = workflow;
		this.users = users;
		this.items = items;
		this.pricing = pricing;
		this.tx = tx;
		this.clock = clock;
	}

	/** A new customer account. */
	public User customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@orders.zaika.test", "9876543210",
				"hash", Role.CUSTOMER));
	}

	/** A new delivery partner account. */
	public User rider() {
		return users.save(new User("Sagar Patil", "r-" + UUID.randomUUID() + "@orders.zaika.test", "9822012345",
				"hash", Role.DELIVERY_PARTNER));
	}

	/** An order of {@code quantity} x the dish, not yet moved (PENDING_PAYMENT), saved and committed. */
	public Order create(long customerId, long menuItemId, int quantity, PaymentMethod method) {
		return tx.execute(status -> {
			MenuItem dish = items.findWithRestaurantById(menuItemId).orElseThrow();
			BigDecimal itemTotal = dish.getPrice().multiply(BigDecimal.valueOf(quantity));
			var bill = pricing.price(itemTotal, dish.getRestaurant().getDeliveryFee(), BigDecimal.ZERO);
			Order order = new Order(users.findById(customerId).orElseThrow(), dish.getRestaurant(), ADDRESS, method,
					bill, null, LocalDateTime.now(clock).plusMinutes(dish.getRestaurant().getDeliveryMinutes()));
			order.addItem(dish, quantity);
			return orders.save(order);
		});
	}

	/**
	 * {@link #create} then walks the order to {@code target} the way the people involved would. CANCELLED is
	 * cancelled by the customer before it was placed (never paid); see {@link #placeThenCancel} for one cancelled
	 * after it reached the kitchen.
	 */
	public Order place(long customerId, long menuItemId, int quantity, PaymentMethod method, OrderStatus target) {
		Order created = create(customerId, menuItemId, quantity, method);
		User rider = target == OrderStatus.OUT_FOR_DELIVERY || target == OrderStatus.DELIVERED ? rider() : null;
		return tx.execute(status -> {
			Order order = orders.findById(created.getId()).orElseThrow();
			long ownerId = order.getRestaurant().getOwner().getId();
			OrderActor owner = OrderActor.restaurant(ownerId);
			switch (target) {
				case PENDING_PAYMENT -> {
				}
				case CANCELLED -> workflow.transition(order, target, OrderActor.customer(customerId), "Ordered by mistake");
				default -> {
					workflow.transition(order, OrderStatus.PLACED, OrderActor.system());
					if (target == OrderStatus.REJECTED) {
						workflow.transition(order, target, owner, "Kitchen closing early");
						break;
					}
					for (OrderStatus next : new OrderStatus[] { OrderStatus.ACCEPTED, OrderStatus.PREPARING,
							OrderStatus.READY_FOR_PICKUP }) {
						if (order.getStatus() == target) {
							break;
						}
						workflow.transition(order, next, owner);
					}
					if (rider != null) {
						order.assignRider(users.findById(rider.getId()).orElseThrow());
						workflow.transition(order, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(rider.getId()));
						if (target == OrderStatus.DELIVERED) {
							workflow.transition(order, target, OrderActor.rider(rider.getId()));
						}
					}
				}
			}
			return order;
		});
	}

	/** Adds another dish line to a saved order, for orders of several dishes. */
	public void addLine(Order order, MenuItem dish, int quantity) {
		tx.executeWithoutResult(status -> orders.findById(order.getId()).orElseThrow()
			.addItem(items.findWithRestaurantById(dish.getId()).orElseThrow(), quantity));
	}

	/** An order placed (PLACED) and then cancelled by its customer, the way a customer cancels from tracking. */
	public Order placeThenCancel(long customerId, long menuItemId, int quantity, PaymentMethod method) {
		Order placed = place(customerId, menuItemId, quantity, method, OrderStatus.PLACED);
		return tx.execute(status -> {
			Order order = orders.findById(placed.getId()).orElseThrow();
			workflow.transition(order, OrderStatus.CANCELLED, OrderActor.customer(customerId), "Ordered by mistake");
			return order;
		});
	}

}
