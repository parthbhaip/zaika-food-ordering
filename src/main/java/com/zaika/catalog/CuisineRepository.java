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

	/** Exact name, any case (names are unique; the table is small). */
	Optional<Cuisine> findByNameIgnoreCase(String name);

	/** How many restaurants list a cuisine, and how many of those are live. */
	record Usage(Long cuisineId, Long restaurants, Long live) {
	}

	/** Usage per cuisine (cuisines no restaurant lists are missing). Uses idx_restaurant_cuisines_cuisine. */
	@Query("""
			select new com.zaika.catalog.CuisineRepository$Usage(c.id, count(r),
				sum(case when r.status = com.zaika.catalog.RestaurantStatus.APPROVED then 1 else 0 end))
			from Restaurant r join r.cuisines c group by c.id""")
	List<Usage> usage();

}
