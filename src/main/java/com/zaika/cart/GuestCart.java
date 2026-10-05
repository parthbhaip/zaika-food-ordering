package com.zaika.cart;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import com.zaika.catalog.MenuItem;

/**
 * A guest's cart, kept in the HTTP session under {@link #SESSION_KEY} until they sign in, when
 * {@link CartService#merge} moves it into their saved cart. Synchronized, since parallel requests of one session
 * (a double-tapped ADD, two tabs) share the instance.
 */
public class GuestCart implements CartContents, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	public static final String SESSION_KEY = GuestCart.class.getName();

	private Long restaurantId;

	private final LinkedHashMap<Long, Line> lines = new LinkedHashMap<>();

	/** Removes the guest cart from the session and returns it, or {@code null} if there is none. */
	public static GuestCart takeFrom(HttpSession session) {
		if (session == null) {
			return null;
		}
		GuestCart cart = (GuestCart) session.getAttribute(SESSION_KEY);
		session.removeAttribute(SESSION_KEY);
		return cart;
	}

	public synchronized boolean isEmpty() {
		return lines.isEmpty();
	}

	@Override
	public synchronized Long restaurantId() {
		return restaurantId;
	}

	@Override
	public synchronized Map<Long, Integer> quantities() {
		Map<Long, Integer> quantities = new LinkedHashMap<>();
		lines.forEach((id, line) -> quantities.put(id, line.quantity()));
		return quantities;
	}

	@Override
	public synchronized BigDecimal seenPrice(long itemId) {
		Line line = lines.get(itemId);
		return line == null ? null : line.price();
	}

	@Override
	public synchronized void put(MenuItem item, int quantity) {
		restaurantId = item.getRestaurant().getId();
		lines.put(item.getId(), new Line(quantity, item.getPrice()));
	}

	@Override
	public synchronized void markSeen(long itemId, BigDecimal price) {
		lines.computeIfPresent(itemId, (id, line) -> new Line(line.quantity(), price));
	}

	@Override
	public synchronized void remove(long itemId) {
		lines.remove(itemId);
		if (lines.isEmpty()) {
			restaurantId = null;
		}
	}

	@Override
	public synchronized void clear() {
		lines.clear();
		restaurantId = null;
	}

	record Line(int quantity, BigDecimal price) implements Serializable {
	}

}
