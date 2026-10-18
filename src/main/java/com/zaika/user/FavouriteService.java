package com.zaika.user;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.catalog.RestaurantCard;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.NotFoundException;

/** A customer's favourite restaurants: the heart on {@code /r/{slug}} and the {@code /account/favourites} list. */
@Service
public class FavouriteService {

	private final FavouriteRepository favourites;

	private final RestaurantRepository restaurants;

	private final UserRepository users;

	private final RestaurantBrowseService browse;

	private final Clock clock;

	public FavouriteService(FavouriteRepository favourites, RestaurantRepository restaurants, UserRepository users,
			RestaurantBrowseService browse, Clock clock) {
		this.favourites = favourites;
		this.restaurants = restaurants;
		this.users = users;
		this.browse = browse;
		this.clock = clock;
	}

	/**
	 * The customer's favourites that are live on Zaika, newest first, as listing cards. A favourite of a suspended
	 * restaurant is kept but not shown, so it comes back if the restaurant does.
	 */
	@Transactional(readOnly = true)
	public List<RestaurantCard> list(long userId) {
		LocalTime now = LocalTime.now(clock);
		return favourites.findByUserIdAndRestaurantStatusOrderByCreatedAtDescIdDesc(userId, RestaurantStatus.APPROVED)
			.stream().map(f -> browse.toCard(f.getRestaurant(), now)).toList();
	}

	@Transactional(readOnly = true)
	public boolean contains(long userId, long restaurantId) {
		return favourites.existsByUserIdAndRestaurantId(userId, restaurantId);
	}

	/**
	 * Saves the restaurant, or removes it when it is already saved. Only live restaurants can be added; removing always
	 * works. The customer's own row is locked so two quick clicks cannot both insert.
	 *
	 * @return whether the restaurant is a favourite afterwards
	 * @throws NotFoundException for an unknown slug, or adding a restaurant that is not live
	 */
	@Transactional
	public boolean toggle(long userId, String slug) {
		User user = users.findForUpdate(userId).orElseThrow(() -> new NotFoundException("User " + userId));
		Restaurant restaurant = restaurants.findBySlug(slug).orElseThrow(() -> new NotFoundException("Restaurant " + slug));
		var existing = favourites.findByUserIdAndRestaurantId(userId, restaurant.getId());
		if (existing.isPresent()) {
			favourites.delete(existing.get());
			return false;
		}
		if (restaurant.getStatus() != RestaurantStatus.APPROVED) {
			throw new NotFoundException("Restaurant " + slug);
		}
		favourites.save(new Favourite(user, restaurant));
		return true;
	}

}
