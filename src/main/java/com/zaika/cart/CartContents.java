package com.zaika.cart;

import java.math.BigDecimal;
import java.util.Map;

import com.zaika.catalog.MenuItem;

/**
 * The dishes in a cart, wherever it is kept: {@link Cart} (a signed-in customer's, in the database) or
 * {@link GuestCart} (a guest's, in the session). {@link CartService} holds the rules and works on either.
 */
interface CartContents {

	/** Restaurant of the dishes in the cart, or {@code null} while it is empty. */
	Long restaurantId();

	/** Menu item id to quantity, in the order the dishes were added. */
	Map<Long, Integer> quantities();

	/** The price the customer last saw for this dish. */
	BigDecimal seenPrice(long itemId);

	/** Sets the dish's quantity (adding it if needed) and remembers its current price as seen. */
	void put(MenuItem item, int quantity);

	/** Remembers {@code price} as the one the customer has now seen. */
	void markSeen(long itemId, BigDecimal price);

	/** The customer's note for the restaurant, or {@code null}. */
	String instructions();

	void setInstructions(String instructions);

	/** Takes the dish out; the note goes too once the cart is empty, being meant for that order. */
	void remove(long itemId);

	/** Empties the cart, note included. */
	void clear();

}
