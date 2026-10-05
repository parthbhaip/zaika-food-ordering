package com.zaika.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CuisineRepository extends JpaRepository<Cuisine, Long> {

	Optional<Cuisine> findBySlug(String slug);

	List<Cuisine> findAllByOrderByNameAsc();

	/** Cuisines that at least one restaurant with this status cooks, by name (the home page shortcuts). */
	@Query("select c from Cuisine c where exists (select 1 from Restaurant r join r.cuisines rc where rc = c and r.status = :status) order by c.name")
	List<Cuisine> findServedBy(RestaurantStatus status);

}
