package com.zaika.owner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.Slugs;
import com.zaika.user.EmailTakenException;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserService;

/**
 * Restaurant sign-up: one transaction creates the owner's account and a {@code PENDING} restaurant, which customers
 * cannot see until an admin approves it.
 */
@Service
public class RestaurantOnboardingService {

	/** Longest stored slug ({@code restaurants.slug}). */
	static final int SLUG_MAX = 80;

	private final UserService userService;

	private final RestaurantRepository restaurants;

	private final CuisineRepository cuisines;

	RestaurantOnboardingService(UserService userService, RestaurantRepository restaurants, CuisineRepository cuisines) {
		this.userService = userService;
		this.restaurants = restaurants;
		this.cuisines = cuisines;
	}

	/** Every cuisine, by name: the choices on the sign-up form. */
	@Transactional(readOnly = true)
	public List<Cuisine> cuisineOptions() {
		return cuisines.findAllByOrderByNameAsc();
	}

	/**
	 * Creates the owner account and the restaurant from a validated form. The restaurant starts PENDING, accepting
	 * orders, with the form's hours and fees, and a slug made from its name (made unique with the area, then a
	 * number).
	 *
	 * @param logoPath the stored logo ({@code StorageService.store}), or {@code null}
	 * @throws EmailTakenException if the email already has an account (nothing is saved)
	 * @throws IllegalArgumentException if a cuisine id does not exist (the form only offers real ones)
	 */
	@Transactional
	public Restaurant register(RestaurantSignupForm form, String logoPath) {
		Set<Long> ids = new HashSet<>(form.getCuisineIds());
		List<Cuisine> chosen = cuisines.findAllById(ids);
		if (ids.isEmpty() || chosen.size() != ids.size()) {
			throw new IllegalArgumentException("Unknown cuisine in " + ids);
		}
		User owner = userService.registerAccount(form.getOwnerName(), form.getEmail(), form.getOwnerPhone(),
				form.getPassword(), Role.RESTAURANT_OWNER);
		Restaurant restaurant = new Restaurant(owner, uniqueSlug(form.getRestaurantName(), form.getArea()),
				form.getRestaurantName());
		restaurant.setTagline(form.getTagline());
		restaurant.setDescription(form.getDescription());
		restaurant.setCuisines(chosen);
		restaurant.setPureVeg(form.isPureVeg());
		restaurant.setPhone(form.getRestaurantPhone());
		restaurant.setAddress(form.getAddress());
		restaurant.setArea(form.getArea());
		restaurant.setCity(form.getCity());
		restaurant.setPincode(form.getPincode());
		restaurant.setLogoPath(logoPath);
		restaurant.setHours(form.getOpensAt(), form.getClosesAt());
		restaurant.setAvgPrepMinutes(form.getAvgPrepMinutes());
		restaurant.setCostForTwo(form.getCostForTwo());
		restaurant.setMinimumOrder(form.getMinimumOrder());
		restaurant.setDeliveryFee(form.getDeliveryFee());
		return restaurants.save(restaurant);
	}

	/**
	 * "Cafe Mocha" -> {@code cafe-mocha}; if taken, {@code cafe-mocha-rajarampuri}, then {@code cafe-mocha-2}, -3 ...
	 * ponytail: check-then-insert; two sign-ups with the same name in the same instant hit the unique index (500).
	 */
	String uniqueSlug(String name, String area) {
		String base = fit(Slugs.slugify(name), SLUG_MAX - 4);
		if (base.isEmpty()) {
			base = "restaurant";
		}
		if (!restaurants.existsBySlug(base)) {
			return base;
		}
		String withArea = fit(base + "-" + Slugs.slugify(area), SLUG_MAX);
		if (!withArea.equals(base) && !restaurants.existsBySlug(withArea)) {
			return withArea;
		}
		for (int n = 2;; n++) {
			String numbered = base + "-" + n;
			if (!restaurants.existsBySlug(numbered)) {
				return numbered;
			}
		}
	}

	/** Cuts a slug to {@code max} characters without leaving a dash at the end. */
	private static String fit(String slug, int max) {
		String cut = slug.length() <= max ? slug : slug.substring(0, max);
		return cut.replaceAll("-+$", "");
	}

}
