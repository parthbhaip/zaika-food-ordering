package com.zaika.catalog;

import java.util.List;

/**
 * Everything the restaurant page shows. The entities are detached but fully loaded for the view: the restaurant with
 * its cuisines, and the visible (not archived) items of each section with only their own columns used.
 *
 * @param card open state, next opening time, delivery estimate and cover photo, as on the listing card
 * @param sections menu sections with the items that pass the filter; empty sections are left out
 * @param dishCount every visible dish, before the filter
 * @param shownCount dishes that pass the filter
 * @param offers short offer lines for the offers strip: the delivery offer, then the coupons open here
 */
public record RestaurantMenu(Restaurant restaurant, RestaurantCard card, List<Section> sections, int dishCount,
		int shownCount, List<Offer> offers) {

	/** Open round the clock (equal opening and closing times). */
	public boolean isOpenAllDay() {
		return restaurant.getOpensAt().equals(restaurant.getClosesAt());
	}

	/** One menu category and its matching items, in menu order. The anchor is {@code #menu-<id>}. */
	public record Section(Long id, String name, List<MenuItem> items) {
	}

	/**
	 * One entry of the offers strip, e.g. "Free delivery above ₹499" / "₹30 delivery fee below that", or a coupon:
	 * "50% off up to ₹100" / "Use WELCOME50 · Min. order ₹149". {@code icon} names a symbol in the icon sprite.
	 */
	public record Offer(String icon, String title, String detail) {
	}

}
