package com.zaika.cart;

import com.zaika.catalog.MenuItem;

/**
 * What {@link CartService#setQuantity} did.
 *
 * @param quantity the dish's quantity in the cart afterwards
 * @param capped more than {@link CartService#MAX_QUANTITY} was asked for, so the limit was used
 */
public record CartChange(Outcome outcome, MenuItem item, int quantity, boolean capped) {

	public enum Outcome {

		/** The dish was not in the cart and now is. */
		ADDED,
		/** A different quantity of a dish already in the cart. */
		UPDATED,
		REMOVED,
		/** Nothing to do (same quantity, or removing a dish that was not there). */
		UNCHANGED,
		/** More was asked for, but the dish is sold out. */
		SOLD_OUT,
		/** More was asked for, but the restaurant is closed or not accepting orders. */
		CLOSED,
		/** The cart holds another restaurant's dishes; the customer must confirm starting a new cart. */
		OTHER_RESTAURANT

	}

}
