package com.zaika.catalog;

import java.time.LocalTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

	/** Every item a customer may see (archived ones hidden, sold-out ones included), in menu order. */
	List<MenuItem> findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long restaurantId);

	/**
	 * Dishes whose name is LIKE {@code pattern} (lower-cased, {@code !} escapes), at restaurants in {@code status},
	 * archived ones excluded, restaurant fetched. Dishes from kitchens taking orders at {@code now} first (accepting
	 * orders and within their hours, the same rule as {@link Restaurant#isOpenAt}), then available dishes, then
	 * bestsellers, then the best-rated kitchens.
	 */
	@Query("""
			select i from MenuItem i join fetch i.restaurant r
			where r.status = :status and i.archived = false and lower(i.name) like :pattern escape '!'
			order by case when r.acceptingOrders = true and (r.opensAt = r.closesAt
					or (r.opensAt < r.closesAt and r.opensAt <= :now and r.closesAt > :now)
					or (r.opensAt > r.closesAt and (r.opensAt <= :now or r.closesAt > :now))) then 0 else 1 end,
				i.available desc, i.bestseller desc, r.ratingAverage desc, r.ratingCount desc, i.name, i.id""")
	List<MenuItem> searchByName(RestaurantStatus status, String pattern, LocalTime now, Limit limit);

	/** How many dishes {@link #searchByName} would find without a limit. */
	@Query("""
			select count(i) from MenuItem i join i.restaurant r
			where r.status = :status and i.archived = false and lower(i.name) like :pattern escape '!'""")
	long countByName(RestaurantStatus status, String pattern);

}
