package com.zaika.owner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.common.NotFoundException;
import com.zaika.storage.ImageKind;
import com.zaika.storage.InvalidImageException;
import com.zaika.storage.StorageService;

/**
 * The owner's restaurant profile ({@code /owner/restaurant}): details, cuisines, pure veg, logo and cover photo, hours
 * and fees. Always the signed-in owner's own restaurant (there is no id to tamper with). The slug never changes, so the
 * page address stays the same after a rename.
 */
@Service
public class RestaurantProfileService {

	private final RestaurantRepository restaurants;

	private final CuisineRepository cuisines;

	private final MenuItemRepository items;

	private final StorageService storage;

	RestaurantProfileService(RestaurantRepository restaurants, CuisineRepository cuisines, MenuItemRepository items,
			StorageService storage) {
		this.restaurants = restaurants;
		this.cuisines = cuisines;
		this.items = items;
		this.storage = storage;
	}

	/** What the profile page needs: the restaurant as saved, every cuisine by name, the prep times to offer. */
	@Transactional(readOnly = true)
	public Profile profile(long ownerId) {
		Restaurant restaurant = restaurantOf(ownerId);
		return new Profile(restaurant, cuisines.findAllByOrderByNameAsc(), prepOptions(restaurant));
	}

	/**
	 * Saves the validated form. A new logo or cover replaces the old file, which is deleted once the change is saved;
	 * a "remove" tick takes the photo off. Nothing is saved (and no new file kept) when a rule fails.
	 *
	 * @throws OwnerRuleException for an unknown cuisine or prep time, pure veg while non-veg dishes are still on the
	 * menu, or an unusable photo
	 */
	@Transactional
	public Restaurant update(long ownerId, RestaurantProfileForm form) {
		Restaurant restaurant = restaurantOf(ownerId);
		Set<Long> ids = new HashSet<>(form.getCuisineIds());
		List<Cuisine> chosen = cuisines.findAllById(ids);
		if (ids.isEmpty() || chosen.size() != ids.size()) {
			throw new OwnerRuleException("cuisineIds", "Pick your cuisines from the list.");
		}
		if (!prepOptions(restaurant).contains(form.getAvgPrepMinutes())) {
			throw new OwnerRuleException("avgPrepMinutes", "Choose a preparation time.");
		}
		if (form.isPureVeg()) {
			checkNoNonVeg(restaurant);
		}
		if (form.hasLogo() || form.isRemoveLogo()) {
			String old = restaurant.getLogoPath();
			restaurant.setLogoPath(form.hasLogo() ? store(ImageKind.LOGO, form.getLogo(), "logo") : null);
			storage.deleteAfterCommit(old);
		}
		if (form.hasCover() || form.isRemoveCover()) {
			String old = restaurant.getCoverPath();
			restaurant.setCoverPath(form.hasCover() ? store(ImageKind.COVER, form.getCover(), "cover") : null);
			storage.deleteAfterCommit(old);
		}
		applyDetails(restaurant, form, chosen);
		return restaurant;
	}

	/** Copies the form's details, hours and fees (not the photos) onto the restaurant. Sign-up uses it too. */
	static void applyDetails(Restaurant restaurant, RestaurantProfileForm form, List<Cuisine> chosen) {
		restaurant.setName(form.getRestaurantName());
		restaurant.setTagline(form.getTagline());
		restaurant.setDescription(form.getDescription());
		restaurant.setCuisines(chosen);
		restaurant.setPureVeg(form.isPureVeg());
		restaurant.setPhone(form.getRestaurantPhone());
		restaurant.setAddress(form.getAddress());
		restaurant.setArea(form.getArea());
		restaurant.setCity(form.getCity());
		restaurant.setPincode(form.getPincode());
		restaurant.setHours(form.getOpensAt(), form.getClosesAt());
		restaurant.setAvgPrepMinutes(form.getAvgPrepMinutes());
		restaurant.setCostForTwo(form.getCostForTwo());
		restaurant.setMinimumOrder(form.getMinimumOrder());
		restaurant.setDeliveryFee(form.getDeliveryFee());
	}

	/** The usual prep times, plus the restaurant's own if it was set to another value. */
	private static List<Integer> prepOptions(Restaurant restaurant) {
		Set<Integer> options = new TreeSet<>(RestaurantProfileForm.PREP_MINUTES);
		options.add(restaurant.getAvgPrepMinutes());
		return List.copyOf(options);
	}

	/** A pure-veg kitchen lists no non-veg dish: they must go (or be marked veg) first. */
	private void checkNoNonVeg(Restaurant restaurant) {
		List<String> names = items.findByRestaurantIdAndArchivedFalseAndVegFalseOrderBySortOrderAscIdAsc(
				restaurant.getId()).stream().map(MenuItem::getName).toList();
		if (names.isEmpty()) {
			return;
		}
		String which = switch (names.size()) {
			case 1 -> "a non-veg dish, " + names.get(0) + ". Delete it or mark it veg";
			case 2 -> "2 non-veg dishes: " + names.get(0) + " and " + names.get(1) + ". Delete them or mark them veg";
			default -> names.size() + " non-veg dishes: " + names.get(0) + ", " + names.get(1) + " and "
					+ (names.size() - 2) + " more. Delete them or mark them veg";
		};
		throw new OwnerRuleException("pureVeg", "Your menu still has " + which + ", then switch to pure veg.");
	}

	private String store(ImageKind kind, MultipartFile file, String field) {
		try {
			return storage.storeForTransaction(kind, file);
		}
		catch (InvalidImageException ex) {
			throw new OwnerRuleException(field, ex.getMessage());
		}
	}

	private Restaurant restaurantOf(long ownerId) {
		return restaurants.findWithCuisinesByOwnerId(ownerId)
			.orElseThrow(() -> new NotFoundException("No restaurant for owner " + ownerId));
	}

	/** The profile page: the restaurant as saved, the cuisines to choose from, the prep times to offer. */
	public record Profile(Restaurant restaurant, List<Cuisine> cuisines, List<Integer> prepMinutes) {
	}

}
