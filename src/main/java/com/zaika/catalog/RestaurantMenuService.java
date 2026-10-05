package com.zaika.catalog;

import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.config.AppProperties;
import com.zaika.coupon.CouponService;

/**
 * The restaurant page ({@code /r/{slug}}) as customers see it: only {@link RestaurantStatus#APPROVED} restaurants,
 * archived dishes hidden, sold-out ones kept (shown disabled). Four queries: restaurant with cuisines, categories,
 * items, coupon offers. The menu is filtered in memory; one restaurant's menu is a few dozen rows.
 */
@Service
public class RestaurantMenuService {

	private final RestaurantRepository restaurants;

	private final MenuCategoryRepository categories;

	private final MenuItemRepository items;

	private final RestaurantBrowseService browse;

	private final AppProperties app;

	private final CouponService coupons;

	private final Formatters fmt;

	private final Clock clock;

	public RestaurantMenuService(RestaurantRepository restaurants, MenuCategoryRepository categories,
			MenuItemRepository items, RestaurantBrowseService browse, AppProperties app, CouponService coupons,
			Formatters fmt, Clock clock) {
		this.restaurants = restaurants;
		this.categories = categories;
		this.items = items;
		this.browse = browse;
		this.app = app;
		this.coupons = coupons;
		this.fmt = fmt;
		this.clock = clock;
	}

	/** The page for {@code slug}; unknown and not-approved restaurants are a 404. */
	@Transactional(readOnly = true)
	public RestaurantMenu menu(String slug, MenuFilter filter) {
		Restaurant restaurant = restaurants.findBySlug(slug)
			.filter(r -> r.getStatus() == RestaurantStatus.APPROVED)
			.orElseThrow(() -> new NotFoundException("No approved restaurant " + slug));

		Map<MenuCategory, List<MenuItem>> byCategory = new LinkedHashMap<>();
		for (MenuCategory category : categories.findByRestaurantIdOrderBySortOrderAscIdAsc(restaurant.getId())) {
			byCategory.put(category, new ArrayList<>());
		}
		List<MenuItem> visible = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId());
		int shown = 0;
		for (MenuItem item : visible) {
			if (filter.matches(item)) {
				// Same persistence context, so the item's category is the very instance loaded above.
				byCategory.get(item.getCategory()).add(item);
				shown++;
			}
		}
		List<RestaurantMenu.Section> sections = byCategory.entrySet().stream()
			.filter(e -> !e.getValue().isEmpty())
			.map(e -> new RestaurantMenu.Section(e.getKey().getId(), e.getKey().getName(), e.getValue()))
			.toList();

		return new RestaurantMenu(restaurant, browse.toCard(restaurant, LocalTime.now(clock)), sections,
				visible.size(), shown, offers(restaurant));
	}

	private List<RestaurantMenu.Offer> offers(Restaurant restaurant) {
		List<RestaurantMenu.Offer> offers = new ArrayList<>();
		if (restaurant.getDeliveryFee().signum() == 0) {
			offers.add(new RestaurantMenu.Offer("bike", "Free delivery", "On every order from this kitchen"));
		}
		else {
			offers.add(new RestaurantMenu.Offer("bike", "Free delivery above " + fmt.price(app.freeDeliveryThreshold()),
					fmt.price(restaurant.getDeliveryFee()) + " delivery fee below that"));
		}
		coupons.offers(restaurant.getId()).forEach(coupon -> offers.add(new RestaurantMenu.Offer("tag",
				coupon.headline(), "Use " + coupon.code() + (coupon.terms().isEmpty() ? "" : " · " + coupon.terms()))));
		return List.copyOf(offers);
	}

}
