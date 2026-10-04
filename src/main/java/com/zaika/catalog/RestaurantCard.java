package com.zaika.catalog;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * What {@code fragments/restaurant-card} shows for one restaurant in a listing.
 *
 * @param imageUrl cover photo URL, or null for the generic food placeholder
 * @param ratingCount 0 shows a "New" pill instead of the average
 * @param deliveryMinutes lower end of the delivery estimate, shown as "25-30 min"
 * @param offer short offer line on the photo ("Free delivery above ₹499"), or null
 * @param opensAt next opening time, shown on closed restaurants; null when unknown
 */
public record RestaurantCard(String slug, String name, String imageUrl, List<String> cuisines,
		BigDecimal ratingAverage, int ratingCount, int deliveryMinutes, BigDecimal costForTwo, boolean pureVeg,
		String offer, boolean openNow, LocalTime opensAt) {
}
