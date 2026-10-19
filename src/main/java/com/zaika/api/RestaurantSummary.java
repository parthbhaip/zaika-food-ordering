package com.zaika.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zaika.catalog.RestaurantCard;

/**
 * A restaurant in a list, as the website's restaurant card shows it.
 *
 * @param imageUrl absolute URL of the cover photo (a generated placeholder when there is none)
 * @param deliveryMinutes lower end of the delivery estimate ("25-30 min" on the website)
 * @param offer short offer line, e.g. "Free delivery", or null
 * @param openNow within today's hours and taking orders
 * @param opensAt next opening time ("HH:mm") of a restaurant closed by its hours; null when open or paused
 */
public record RestaurantSummary(String slug, String name, String imageUrl, List<String> cuisines, Rating rating,
		int deliveryMinutes, BigDecimal costForTwo, boolean pureVeg, String offer, boolean openNow,
		@JsonFormat(pattern = "HH:mm") LocalTime opensAt) {

	static RestaurantSummary of(RestaurantCard card, String baseUrl) {
		return new RestaurantSummary(card.slug(), card.name(), absolute(baseUrl, card.imageUrl()), card.cuisines(),
				Rating.of(card.ratingAverage(), card.ratingCount()), card.deliveryMinutes(), card.costForTwo(),
				card.pureVeg(), card.offer(), card.openNow(), card.opensAt());
	}

	/** Site paths ({@code /img/...}) as absolute URLs on {@code app.base-url}, for the app to load directly. */
	static String absolute(String baseUrl, String path) {
		return path == null ? null : baseUrl + path;
	}

	/**
	 * Customer rating.
	 *
	 * @param average one decimal (4.3); null while nobody has rated yet ("New")
	 */
	public record Rating(BigDecimal average, int count) {

		static Rating of(BigDecimal average, int count) {
			return new Rating(count == 0 || average == null ? null : average.setScale(1, RoundingMode.HALF_UP), count);
		}

	}

}
