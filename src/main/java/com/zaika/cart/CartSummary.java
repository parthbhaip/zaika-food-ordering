package com.zaika.cart;

import java.math.BigDecimal;

/**
 * The cart in one line, for the header badge and the phone "View cart" bar. Not re-checked against the menu (the
 * cart page does that): a customer's total uses today's menu prices (one query), a guest's the prices last seen.
 *
 * @param count dishes, summing quantities
 * @param restaurantName {@code null} while the cart is empty
 */
public record CartSummary(int count, BigDecimal itemTotal, String restaurantName) {

	static final CartSummary EMPTY = new CartSummary(0, BigDecimal.ZERO.setScale(2), null);

	/** For the JPQL aggregate in {@link CartRepository#summarize}, whose sums are null without rows. */
	CartSummary(Long count, BigDecimal itemTotal, String restaurantName) {
		this(count == null ? 0 : Math.toIntExact(count), itemTotal == null ? EMPTY.itemTotal() : itemTotal.setScale(2),
				restaurantName);
	}

}
