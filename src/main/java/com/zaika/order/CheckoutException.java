package com.zaika.order;

/**
 * Why an order was not placed, as the customer reads it. Thrown inside the checkout transaction, so nothing of the
 * attempt is kept (no order, no coupon use, no new address). {@code backToCart}: the cart itself has to change first
 * (empty, restaurant closed, below the minimum), so the customer goes back to the cart instead of the checkout page.
 */
public class CheckoutException extends RuntimeException {

	private final boolean backToCart;

	CheckoutException(String message, boolean backToCart) {
		super(message);
		this.backToCart = backToCart;
	}

	public boolean isBackToCart() {
		return backToCart;
	}

}
