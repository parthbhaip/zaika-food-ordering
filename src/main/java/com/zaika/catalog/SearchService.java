package com.zaika.catalog;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.storage.ImageUrls;

/**
 * Site search over approved restaurants and their dishes: the {@code /search} page (two groups) and the header's
 * type-ahead suggestions. Matching is a case-insensitive "contains" on restaurant, cuisine and dish names.
 */
@Service
public class SearchService {

	/** Most dishes the search page lists; the count says when there are more. */
	public static final int DISH_LIMIT = 24;

	/** Matches the header suggests (plus a "See all results" link). */
	public static final int SUGGESTION_LIMIT = 5;

	/** Shorter text gets no suggestions: one letter matches nearly everything. */
	public static final int MIN_SUGGEST_LENGTH = 2;

	private final RestaurantBrowseService browse;

	private final RestaurantRepository restaurants;

	private final MenuItemRepository items;

	private final ImageUrls images;

	private final Clock clock;

	public SearchService(RestaurantBrowseService browse, RestaurantRepository restaurants, MenuItemRepository items,
			ImageUrls images, Clock clock) {
		this.browse = browse;
		this.restaurants = restaurants;
		this.items = items;
		this.images = images;
		this.clock = clock;
	}

	/** {@code /search} or {@code /search?q=misal+pav}. */
	public static String url(String q) {
		return q == null ? "/search" : "/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
	}

	/**
	 * Restaurants (first listing page: name, cuisine or a dish matches) and dishes (name matches, at most
	 * {@link #DISH_LIMIT}) for {@code q}, which must be normalized and not null.
	 */
	@Transactional(readOnly = true)
	public Results search(String q) {
		RestaurantFilter filter = new RestaurantFilter();
		filter.setQ(q);
		Page<RestaurantCard> found = browse.browse(filter);

		String pattern = RestaurantBrowseService.likePattern(q);
		LocalTime now = LocalTime.now(clock);
		List<MenuItem> dishes = items.searchByName(RestaurantStatus.APPROVED, pattern, now, Limit.of(DISH_LIMIT));
		long dishCount = dishes.size() < DISH_LIMIT ? dishes.size()
				: items.countByName(RestaurantStatus.APPROVED, pattern);
		return new Results(q, found.getContent(), found.getTotalElements(),
				dishes.stream().map(item -> toHit(item, now)).toList(), dishCount);
	}

	/**
	 * Up to {@link #SUGGESTION_LIMIT} matches for the header box: restaurants first (name or cuisine), then dishes.
	 * Empty for text shorter than {@link #MIN_SUGGEST_LENGTH}. Two queries at most.
	 */
	@Transactional(readOnly = true)
	public List<Suggestion> suggest(String q) {
		if (q == null || q.length() < MIN_SUGGEST_LENGTH) {
			return List.of();
		}
		String pattern = RestaurantBrowseService.likePattern(q);
		List<Suggestion> suggestions = new ArrayList<>();
		for (Restaurant r : restaurants.suggest(RestaurantStatus.APPROVED, pattern, Limit.of(SUGGESTION_LIMIT))) {
			suggestions.add(Suggestion.of(Suggestion.Kind.RESTAURANT, r.getName(), q, "Restaurant · " + r.getArea(),
					images.logo(r), "/r/" + r.getSlug(), false));
		}
		int room = SUGGESTION_LIMIT - suggestions.size();
		if (room > 0) {
			for (MenuItem item : items.searchByName(RestaurantStatus.APPROVED, pattern, LocalTime.now(clock),
					Limit.of(room))) {
				suggestions.add(Suggestion.of(Suggestion.Kind.DISH, item.getName(), q,
						"Dish at " + item.getRestaurant().getName(), images.dish(item), dishUrl(item), item.isVeg()));
			}
		}
		return suggestions;
	}

	private DishHit toHit(MenuItem item, LocalTime now) {
		Restaurant r = item.getRestaurant();
		return new DishHit(item.getId(), item.getName(), item.getDescription(), item.getPrice(), item.isVeg(),
				item.isBestseller(), item.isAvailable(), images.dish(item), r.getSlug(), r.getName(), r.getArea(),
				r.isAcceptingOrders() && r.isOpenAt(now), dishUrl(item));
	}

	/** The dish on its restaurant's menu, searched for by name and scrolled into view. */
	static String dishUrl(MenuItem item) {
		MenuFilter filter = new MenuFilter();
		filter.setQ(item.getName());
		return filter.url(item.getRestaurant().getSlug()) + "#dish-" + item.getId();
	}

	/**
	 * What {@code /search?q=} shows.
	 *
	 * @param restaurants the first listing page of matching restaurants
	 * @param restaurantCount all matching restaurants (more than shown means "See all" links to the listing)
	 * @param dishCount all matching dishes (more than {@link #DISH_LIMIT} are not listed)
	 */
	public record Results(String q, List<RestaurantCard> restaurants, long restaurantCount, List<DishHit> dishes,
			long dishCount) {

		public boolean isEmpty() {
			return restaurantCount == 0 && dishCount == 0;
		}

	}

	/** One matching dish with its restaurant. {@code url} opens the restaurant's menu at that dish. */
	public record DishHit(Long id, String name, String description, BigDecimal price, boolean veg, boolean bestseller,
			boolean available, String imageUrl, String restaurantSlug, String restaurantName, String restaurantArea,
			boolean restaurantOpen, String url) {
	}

	/**
	 * One header suggestion. The label is split around the first occurrence of the search text so the template can
	 * bold it ({@code match} is empty when the label does not contain it, e.g. a cuisine match).
	 */
	public record Suggestion(Kind kind, String before, String match, String after, String detail, String imageUrl,
			String url, boolean veg) {

		public enum Kind {
			RESTAURANT, DISH
		}

		static Suggestion of(Kind kind, String label, String q, String detail, String imageUrl, String url,
				boolean veg) {
			String lower = label.toLowerCase(Locale.ROOT);
			String needle = q.toLowerCase(Locale.ROOT);
			// Lower-casing can change the length of some scripts; then offsets would not line up, so skip the bold.
			boolean sameLength = lower.length() == label.length() && needle.length() == q.length();
			int at = sameLength ? lower.indexOf(needle) : -1;
			if (at < 0) {
				return new Suggestion(kind, label, "", "", detail, imageUrl, url, veg);
			}
			int end = at + q.length();
			return new Suggestion(kind, label.substring(0, at), label.substring(at, end), label.substring(end), detail,
					imageUrl, url, veg);
		}

		public String label() {
			return before + match + after;
		}

	}

}
