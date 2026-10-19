package com.zaika.api;

import static com.zaika.api.RestaurantSummary.absolute;

import java.math.BigDecimal;
import java.util.List;

import com.zaika.catalog.SearchService;

/**
 * Search results in two groups, as {@code /search} shows them.
 *
 * @param q the search text as it was used (trimmed, at most 80 characters)
 * @param restaurants the first page of matching restaurants (name, cuisine or a dish); the rest come from
 *        {@code GET /api/v1/restaurants?q=}
 * @param restaurantCount every matching restaurant
 * @param dishes matching dishes by name, at most 24
 * @param dishCount every matching dish
 */
public record SearchResponse(String q, List<RestaurantSummary> restaurants, long restaurantCount, List<DishHit> dishes,
		long dishCount) {

	static SearchResponse of(SearchService.Results results, String baseUrl) {
		return new SearchResponse(results.q(),
				results.restaurants().stream().map(card -> RestaurantSummary.of(card, baseUrl)).toList(),
				results.restaurantCount(),
				results.dishes()
					.stream()
					.map(d -> new DishHit(d.id(), d.name(), d.description(), d.price(), d.veg(), d.bestseller(),
							d.available(), absolute(baseUrl, d.imageUrl()),
							new RestaurantRef(d.restaurantSlug(), d.restaurantName(), d.restaurantArea(),
									d.restaurantOpen())))
					.toList(),
				results.dishCount());
	}

	/** A matching dish and where it is cooked. {@code available} is false while sold out. */
	public record DishHit(long id, String name, String description, BigDecimal price, boolean veg, boolean bestseller,
			boolean available, String imageUrl, RestaurantRef restaurant) {
	}

	/** The restaurant of a dish; {@code slug} opens it with {@code GET /api/v1/restaurants/{slug}}. */
	public record RestaurantRef(String slug, String name, String area, boolean openNow) {
	}

}
