package com.zaika.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

	/** The restaurant page lookup, cuisines included (one query). */
	@EntityGraph(attributePaths = "cuisines")
	Optional<Restaurant> findBySlug(String slug);

	/** The owner's own restaurant (an owner has at most one). */
	Optional<Restaurant> findByOwnerId(Long ownerId);

	/**
	 * Loads the cuisines of a page of restaurants in one query. Called inside the transaction that loaded the page,
	 * it fills the cuisine collections of those same managed instances (a fetch join cannot be paged in SQL).
	 */
	@EntityGraph(attributePaths = "cuisines")
	List<Restaurant> findWithCuisinesByIdIn(Collection<Long> ids);

}
