package com.zaika.catalog;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

	/** The restaurant page lookup, cuisines included (one query). */
	@EntityGraph(attributePaths = "cuisines")
	Optional<Restaurant> findBySlug(String slug);

	/** The owner's own restaurant (an owner has at most one). */
	Optional<Restaurant> findByOwnerId(Long ownerId);

}
