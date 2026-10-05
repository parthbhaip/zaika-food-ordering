package com.zaika.config;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.config.DemoData.Account;
import com.zaika.config.DemoData.Deal;
import com.zaika.config.DemoData.Dish;
import com.zaika.config.DemoData.Place;
import com.zaika.config.DemoData.Section;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.user.AddressForm;
import com.zaika.user.AddressLabel;
import com.zaika.user.AddressService;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Loads {@link DemoData} on startup when {@code app.seed-demo=true} (dev; SEED_DEMO in mysql). Idempotent: cuisines,
 * accounts, restaurants and coupons are looked up by slug, email or code and only created when missing, and a restaurant's menu is
 * only written together with the restaurant, so a restart changes nothing. An existing account with a demo email is
 * reused, never changed. Runs after {@code AdminBootstrap}, so a configured ADMIN_EMAIL wins over the demo admin.
 */
@Component
@Order(DemoDataSeeder.ORDER)
@ConditionalOnBooleanProperty("app.seed-demo")
class DemoDataSeeder implements ApplicationRunner {

	/** After AdminBootstrap (order 0). */
	static final int ORDER = 10;

	private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

	private final UserRepository users;

	private final AddressService addresses;

	private final CuisineRepository cuisines;

	private final RestaurantRepository restaurants;

	private final MenuCategoryRepository categories;

	private final MenuItemRepository items;

	private final CouponRepository coupons;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	DemoDataSeeder(UserRepository users, AddressService addresses, CuisineRepository cuisines,
			RestaurantRepository restaurants, MenuCategoryRepository categories, MenuItemRepository items,
			CouponRepository coupons, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.addresses = addresses;
		this.cuisines = cuisines;
		this.restaurants = restaurants;
		this.categories = categories;
		this.items = items;
		this.coupons = coupons;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		// BCrypt is slow on purpose; every demo account shares one password, so it is hashed once per run
		String passwordHash = passwordEncoder.encode(DemoData.PASSWORD);
		int createdAccounts = 0;
		for (Account account : DemoData.ACCOUNTS) {
			if (account.role() == Role.ADMIN && users.existsByRole(Role.ADMIN)) {
				continue;
			}
			if (users.existsByEmailIgnoreCase(account.email())) {
				continue;
			}
			User user = createUser(account, passwordHash);
			createdAccounts++;
			if (account.email().equals(DemoData.CUSTOMER_EMAIL)) {
				addresses.create(user.getId(), customerHome());
			}
		}

		Map<String, Cuisine> cuisinesByName = new HashMap<>();
		for (String name : DemoData.CUISINES) {
			Cuisine cuisine = new Cuisine(name);
			cuisinesByName.put(name, cuisines.findBySlug(cuisine.getSlug()).orElseGet(() -> cuisines.save(cuisine)));
		}

		int createdRestaurants = 0;
		for (Place place : DemoData.RESTAURANTS) {
			if (restaurants.findBySlug(place.slug()).isPresent()) {
				continue;
			}
			Optional<User> owner = ownerFor(place, passwordHash);
			if (owner.isPresent()) {
				createRestaurant(place, owner.get(), cuisinesByName);
				createdRestaurants++;
			}
		}
		int createdCoupons = createCoupons();
		if (createdAccounts + createdRestaurants + createdCoupons > 0) {
			log.info("Demo data: added {} accounts, {} restaurants and {} coupons", createdAccounts,
					createdRestaurants, createdCoupons);
		}
	}

	/** The demo coupons missing by code; a restaurant coupon whose restaurant was skipped is skipped too. */
	private int createCoupons() {
		LocalDateTime start = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay();
		int created = 0;
		for (Deal deal : DemoData.COUPONS) {
			if (coupons.findByCode(deal.code()).isPresent()) {
				continue;
			}
			Restaurant restaurant = deal.restaurantSlug() == null ? null
					: restaurants.findBySlug(deal.restaurantSlug()).orElse(null);
			if (deal.restaurantSlug() != null && restaurant == null) {
				log.warn("Demo data: skipped coupon {} because restaurant {} does not exist", deal.code(),
						deal.restaurantSlug());
				continue;
			}
			Coupon coupon = new Coupon(deal.code(), deal.description(), deal.type(), BigDecimal.valueOf(deal.value()),
					start);
			coupon.setMaxDiscount(deal.maxDiscount() == null ? null : BigDecimal.valueOf(deal.maxDiscount()));
			coupon.setMinOrder(BigDecimal.valueOf(deal.minOrder()));
			coupon.setPerUserLimit(deal.perUserLimit());
			coupon.setTotalLimit(deal.totalLimit());
			coupon.setFirstOrderOnly(deal.firstOrderOnly());
			coupon.setRestaurant(restaurant);
			if (deal.months() != null) {
				coupon.setValidity(start, start.plusMonths(deal.months()));
			}
			coupons.save(coupon);
			created++;
		}
		return created;
	}

	/** The place's owner account, created if missing; empty (with a warning) if the email belongs to someone else. */
	private Optional<User> ownerFor(Place place, String passwordHash) {
		Optional<User> existing = users.findByEmailIgnoreCase(place.owner().email());
		if (existing.isEmpty()) {
			return Optional.of(createUser(place.owner(), passwordHash));
		}
		User user = existing.get();
		if (user.getRole() != Role.RESTAURANT_OWNER || restaurants.findByOwnerId(user.getId()).isPresent()) {
			log.warn("Demo data: skipped restaurant {} because {} is not a free restaurant owner account", place.slug(),
					user.getEmail());
			return Optional.empty();
		}
		return Optional.of(user);
	}

	private User createUser(Account account, String passwordHash) {
		return users.save(new User(account.name(), account.email(), account.phone(), passwordHash, account.role()));
	}

	private void createRestaurant(Place place, User owner, Map<String, Cuisine> cuisinesByName) {
		Restaurant restaurant = new Restaurant(owner, place.slug(), place.name());
		restaurant.setTagline(place.tagline());
		restaurant.setDescription(place.description());
		restaurant.setCuisines(place.cuisines().stream().map(cuisinesByName::get).toList());
		restaurant.setPhone(place.phone());
		restaurant.setAddress(place.address());
		restaurant.setArea(place.area());
		restaurant.setCity(DemoData.CITY);
		restaurant.setPincode(place.pincode());
		restaurant.setCostForTwo(place.costForTwo());
		restaurant.setMinimumOrder(place.minimumOrder());
		restaurant.setDeliveryFee(place.deliveryFee());
		restaurant.setAvgPrepMinutes(place.prepMinutes());
		restaurant.setHours(place.opensAt(), place.closesAt());
		restaurant.setPureVeg(place.pureVeg());
		restaurant.changeStatus(place.status(), null);
		restaurant.setRating(place.rating(), place.ratingCount());
		restaurants.save(restaurant);

		for (int s = 0; s < place.menu().size(); s++) {
			Section section = place.menu().get(s);
			MenuCategory category = categories.save(new MenuCategory(restaurant, section.name(), s));
			List<Dish> dishes = section.dishes();
			for (int d = 0; d < dishes.size(); d++) {
				Dish dish = dishes.get(d);
				MenuItem item = new MenuItem(category, dish.name(), dish.price(), dish.veg());
				item.setDescription(dish.description());
				item.setBestseller(dish.bestseller());
				item.setSpiceLevel(dish.spiceLevel());
				item.setAvailable(!dish.soldOut());
				item.setSortOrder(d);
				items.save(item);
			}
		}
	}

	private static AddressForm customerHome() {
		AddressForm form = new AddressForm();
		form.setLabel(AddressLabel.HOME);
		form.setHouse("Flat 302, Shree Ganesh Apartments");
		form.setStreet("Rajarampuri 5th Lane");
		form.setLandmark("Janata Bazar");
		form.setCity(DemoData.CITY);
		form.setPincode("416008");
		form.setMakeDefault(true);
		return form;
	}

}
