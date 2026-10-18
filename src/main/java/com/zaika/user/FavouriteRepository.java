package com.zaika.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.zaika.catalog.RestaurantStatus;

public interface FavouriteRepository extends JpaRepository<Favourite, Long> {

	boolean existsByUserIdAndRestaurantId(long userId, long restaurantId);

	Optional<Favourite> findByUserIdAndRestaurantId(long userId, long restaurantId);

	@EntityGraph(attributePaths = { "restaurant", "restaurant.cuisines" })
	List<Favourite> findByUserIdAndRestaurantStatusOrderByCreatedAtDescIdDesc(long userId, RestaurantStatus status);

}
