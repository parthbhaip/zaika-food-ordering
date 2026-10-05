package com.zaika.cart;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import com.zaika.catalog.MenuItem;
import com.zaika.catalog.Restaurant;

/**
 * The cart as the customer sees it, after re-checking every dish against the menu.
 *
 * @param restaurant where the dishes come from; {@code null} when the cart is empty
 * @param takingOrders the restaurant is within its hours and accepting orders
 * @param opensAt when a restaurant closed by its hours opens again; {@code null} otherwise
 * @param itemCount dishes in the cart, summing quantities
 * @param instructions the customer's note for the restaurant, or {@code null}
 * @param notices what changed since the customer last looked (price changes, dishes taken out)
 */
public record CartView(Restaurant restaurant, boolean takingOrders, LocalTime opensAt, List<Line> lines,
		BigDecimal itemTotal, int itemCount, String instructions, List<String> notices) {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	static CartView empty(List<String> notices) {
		return new CartView(null, false, null, List.of(), ZERO, 0, null, notices);
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/** How much more food the restaurant's minimum order needs; zero once it is reached (or the cart is empty). */
	public BigDecimal minimumOrderShortfall() {
		return isEmpty() ? ZERO : restaurant.getMinimumOrder().subtract(itemTotal).max(ZERO);
	}

	/** Not empty, the restaurant is taking orders and the minimum order is reached. */
	public boolean canCheckout() {
		return !isEmpty() && takingOrders && minimumOrderShortfall().signum() == 0;
	}

	/** One dish: its current price and the quantity in the cart. */
	public record Line(MenuItem item, int quantity, BigDecimal lineTotal) {

		/** At the per-dish limit, so the "+" button is off. */
		public boolean atMax() {
			return quantity >= CartService.MAX_QUANTITY;
		}

	}

}
