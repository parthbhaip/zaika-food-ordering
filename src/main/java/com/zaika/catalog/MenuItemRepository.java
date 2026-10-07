package com.zaika.catalog;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

	/** The dish with its restaurant. */
	@EntityGraph(attributePaths = "restaurant")
	Optional<MenuItem> findWithRestaurantById(Long id);

	/** These dishes with their restaurants; ids that do not exist are simply absent. */
	@EntityGraph(attributePaths = "restaurant")
	List<MenuItem> findWithRestaurantByIdIn(Collection<Long> ids);

	/** The restaurant's dish with this id, archived ones excluded; another restaurant's id finds nothing. */
	Optional<MenuItem> findByIdAndRestaurantIdAndArchivedFalse(Long id, Long restaurantId);

	/** Dishes in the category that are not archived (uses idx_menu_items_category). */
	long countByCategoryIdAndArchivedFalse(Long categoryId);

	/** Whether any dish (archived or not) still points at the category. */
	boolean existsByCategoryId(Long categoryId);

	/** The highest sort order in use at the restaurant (0 without dishes). */
	@Query("select coalesce(max(i.sortOrder), 0) from MenuItem i where i.restaurant.id = :restaurantId")
	int maxSortOrder(Long restaurantId);

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
