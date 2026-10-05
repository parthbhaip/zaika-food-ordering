package com.zaika.support;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Builds catalog rows for page tests (picked up by component scanning in every {@code @SpringBootTest}):
 * <pre>fixtures.restaurant("noor", "Noor Biryani").cuisines("Biryani").rated("4.5", 200).dishes("Chicken Biryani").save();</pre>
 * Each restaurant gets its own owner account {@code <slug>@fixtures.zaika.test} and one "Menu" category. Defaults:
 * APPROVED, accepting orders, non-veg, unrated, 20 min prep, ₹400 for two, ₹30 delivery, open 11:00-23:00.
 * Rows are committed (each repository call is its own transaction); use a test database of your own.
 */
@Component
public class CatalogFixtures {

	private final UserRepository users;

	private final CuisineRepository cuisines;

	private final RestaurantRepository restaurants;

	private final MenuCategoryRepository categories;

	private final MenuItemRepository items;

	public CatalogFixtures(UserRepository users, CuisineRepository cuisines, RestaurantRepository restaurants,
			MenuCategoryRepository categories, MenuItemRepository items) {
		this.users = users;
		this.cuisines = cuisines;
		this.restaurants = restaurants;
		this.categories = categories;
		this.items = items;
	}

	public Builder restaurant(String slug, String name) {
		return new Builder(slug, name);
	}

	/** The cuisine with this name, created if missing. */
	public Cuisine cuisine(String name) {
		Cuisine probe = new Cuisine(name);
		return cuisines.findBySlug(probe.getSlug()).orElseGet(() -> cuisines.save(probe));
	}

	Restaurant save(Builder b) {
		User owner = users.save(new User("Owner of " + b.name, b.slug + "@fixtures.zaika.test", "9876543210",
				"{noop}x", Role.RESTAURANT_OWNER));
		Restaurant r = new Restaurant(owner, b.slug, b.name);
		r.setCuisines(b.cuisineNames.stream().map(this::cuisine).toList());
		r.setPhone("9876543210");
		r.setAddress("12, Main Road");
		r.setArea("Rajarampuri");
		r.setCity("Kolhapur");
		r.setPincode("416008");
		r.setCostForTwo(BigDecimal.valueOf(b.costForTwo));
		r.setDeliveryFee(BigDecimal.valueOf(b.deliveryFee));
		r.setAvgPrepMinutes(b.prepMinutes);
		r.setHours(LocalTime.parse(b.opensAt), LocalTime.parse(b.closesAt));
		r.setPureVeg(b.pureVeg);
		r.setAcceptingOrders(b.acceptingOrders);
		r.setRating(new BigDecimal(b.rating), b.ratingCount);
		r.changeStatus(b.status, b.status.needsReason() ? "Fixture" : null);
		restaurants.save(r);
		MenuCategory menu = categories.save(new MenuCategory(r, "Menu", 1));
		for (String dish : b.dishes) {
			items.save(new MenuItem(menu, dish, new BigDecimal("199"), b.pureVeg));
		}
		for (String dish : b.archivedDishes) {
			MenuItem item = new MenuItem(menu, dish, new BigDecimal("199"), b.pureVeg);
			item.setArchived(true);
			items.save(item);
		}
		return r;
	}

	public final class Builder {

		private final String slug;

		private final String name;

		private final List<String> cuisineNames = new ArrayList<>();

		private final List<String> dishes = new ArrayList<>();

		private final List<String> archivedDishes = new ArrayList<>();

		private boolean pureVeg;

		private String rating = "0";

		private int ratingCount;

		private int prepMinutes = 20;

		private int costForTwo = 400;

		private int deliveryFee = 30;

		private String opensAt = "11:00";

		private String closesAt = "23:00";

		private boolean acceptingOrders = true;

		private RestaurantStatus status = RestaurantStatus.APPROVED;

		private Builder(String slug, String name) {
			this.slug = slug;
			this.name = name;
		}

		public Builder cuisines(String... names) {
			cuisineNames.addAll(List.of(names));
			return this;
		}

		public Builder pureVeg() {
			pureVeg = true;
			return this;
		}

		public Builder rated(String average, int count) {
			rating = average;
			ratingCount = count;
			return this;
		}

		public Builder prep(int minutes) {
			prepMinutes = minutes;
			return this;
		}

		public Builder costForTwo(int rupees) {
			costForTwo = rupees;
			return this;
		}

		public Builder deliveryFee(int rupees) {
			deliveryFee = rupees;
			return this;
		}

		public Builder hours(String opens, String closes) {
			opensAt = opens;
			closesAt = closes;
			return this;
		}

		public Builder paused() {
			acceptingOrders = false;
			return this;
		}

		public Builder status(RestaurantStatus value) {
			status = value;
			return this;
		}

		public Builder dishes(String... names) {
			dishes.addAll(List.of(names));
			return this;
		}

		public Builder archivedDishes(String... names) {
			archivedDishes.addAll(List.of(names));
			return this;
		}

		public Restaurant save() {
			return CatalogFixtures.this.save(this);
		}

	}

}
