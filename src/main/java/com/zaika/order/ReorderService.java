package com.zaika.order;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;

/**
 * "Reorder": puts the dishes of one of the customer's finished orders back in their cart, in the same quantities.
 * Dishes that were taken off the menu or are sold out are left out (and named); a dish whose price changed goes in at
 * today's price, and the cart page says so once (it remembers the price paid last time as the one the customer saw).
 * The cart afterwards holds exactly this order's dishes.
 */
@Service
public class ReorderService {

	private final OrderRepository orders;

	private final MenuItemRepository menu;

	private final CartService carts;

	private final Formatters fmt;

	ReorderService(OrderRepository orders, MenuItemRepository menu, CartService carts, Formatters fmt) {
		this.orders = orders;
		this.menu = menu;
		this.carts = carts;
		this.fmt = fmt;
	}

	/**
	 * @throws NotFoundException for an unknown code or someone else's order
	 * @throws ReorderException when the order is still open, the restaurant is not taking orders, or no dish is
	 *         available; the cart is left as it was
	 */
	@Transactional
	public Reorder reorder(long customerId, String code) {
		Order order = orders.findByCodeAndCustomerId(code, customerId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for customer " + customerId));
		Restaurant restaurant = order.getRestaurant();
		if (!order.getStatus().isFinal()) {
			throw new ReorderException(code + " is still open. You can order it again once it's done.");
		}
		if (restaurant.getStatus() != RestaurantStatus.APPROVED) {
			throw new ReorderException(restaurant.getName() + " is not taking orders on Zaika right now.");
		}
		if (!carts.isTakingOrders(restaurant)) {
			throw new ReorderException(restaurant.isAcceptingOrders()
					? restaurant.getName() + " is closed now. It opens at " + fmt.timeOfDay(restaurant.getOpensAt())
							+ ", so order again then."
					: restaurant.getName() + " has paused new orders for a little while. Try again soon.");
		}
		Map<Long, MenuItem> current = menu.findWithRestaurantByIdIn(order.getItems().stream()
			.map(OrderItem::getMenuItem).filter(Objects::nonNull).map(MenuItem::getId).toList())
			.stream().collect(Collectors.toMap(MenuItem::getId, Function.identity()));
		List<CartService.Pick> picks = new ArrayList<>();
		Set<String> leftOut = new LinkedHashSet<>();
		for (OrderItem line : order.getItems()) {
			MenuItem item = line.getMenuItem() == null ? null : current.get(line.getMenuItem().getId());
			if (item == null || !item.isOrderable()) {
				leftOut.add(line.getName());
			}
			else {
				picks.add(new CartService.Pick(item, line.getQuantity(), line.getUnitPrice()));
			}
		}
		if (picks.isEmpty()) {
			throw new ReorderException("None of the dishes from " + code + " are available at " + restaurant.getName()
					+ " right now. Have a look at today's menu.");
		}
		boolean replaced = carts.replaceWith(new CartOwner.Customer(customerId), picks);
		return new Reorder(code, picks.size(), List.copyOf(leftOut), replaced);
	}

	/**
	 * What {@link #reorder} did.
	 *
	 * @param dishes how many of the order's dishes went into the cart
	 * @param leftOut names of the dishes that are not available now
	 * @param replaced the cart held other dishes, which were taken out
	 */
	public record Reorder(String code, int dishes, List<String> leftOut, boolean replaced) {

		/** "Added 3 dishes from ZK-26-000123 to your cart." */
		public String message() {
			String what = dishes == 1 ? "1 dish" : dishes + " dishes";
			return replaced ? "Your cart now has the " + what + " from " + code + ". We took out what was in it before."
					: "Added " + what + " from " + code + " to your cart.";
		}

		/** "Bhakri and Solkadhi are not available right now, so we left them out.", or null when nothing was. */
		public String leftOutNotice() {
			if (leftOut.isEmpty()) {
				return null;
			}
			int last = leftOut.size() - 1;
			String names = last == 0 ? leftOut.get(0)
					: String.join(", ", leftOut.subList(0, last)) + " and " + leftOut.get(last);
			return names + (last == 0 ? " is not available right now, so we left it out."
					: " are not available right now, so we left them out.");
		}

	}

}
