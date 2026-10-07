package com.zaika.owner;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;

/** What the owner panel shows. Everything is looked up by the signed-in owner's id, never by a URL parameter. */
@Service
public class OwnerDashboardService {

	private final RestaurantRepository restaurants;

	OwnerDashboardService(RestaurantRepository restaurants) {
		this.restaurants = restaurants;
	}

	/** The owner's restaurant with its cuisines, or empty if this owner has none. */
	@Transactional(readOnly = true)
	public Optional<Restaurant> restaurantOf(long ownerId) {
		return restaurants.findWithCuisinesByOwnerId(ownerId);
	}

}
