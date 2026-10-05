package com.zaika.catalog;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

	/** Every item a customer may see (archived ones hidden, sold-out ones included), in menu order. */
	List<MenuItem> findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long restaurantId);

	/**
	 * Dishes whose name is LIKE {@code pattern} (lower-cased, {@code !} escapes), at restaurants in {@code status},
	 * archived ones excluded, restaurant fetched. Available dishes first, then bestsellers, then the best-rated
	 * kitchens.
	 */
	@Query("""
			select i from MenuItem i join fetch i.restaurant r
			where r.status = :status and i.archived = false and lower(i.name) like :pattern escape '!'
			order by i.available desc, i.bestseller desc, r.ratingAverage desc, r.ratingCount desc, i.name, i.id""")
	List<MenuItem> searchByName(RestaurantStatus status, String pattern, Limit limit);

	/** How many dishes {@link #searchByName} would find without a limit. */
	@Query("""
			select count(i) from MenuItem i join i.restaurant r
			where r.status = :status and i.archived = false and lower(i.name) like :pattern escape '!'""")
	long countByName(RestaurantStatus status, String pattern);

}
