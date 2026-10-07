package com.zaika.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

	/** Menu order: sort order, then creation order. Uses idx_menu_categories_restaurant. */
	List<MenuCategory> findByRestaurantIdOrderBySortOrderAscIdAsc(Long restaurantId);

	/** The categories the owner manages (archived ones left out), in menu order. */
	List<MenuCategory> findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long restaurantId);

	/** The restaurant's live category with this id; another restaurant's id finds nothing. */
	Optional<MenuCategory> findByIdAndRestaurantIdAndArchivedFalse(Long id, Long restaurantId);

	/** The highest sort order in use at the restaurant (0 without categories). */
	@Query("select coalesce(max(c.sortOrder), 0) from MenuCategory c where c.restaurant.id = :restaurantId")
	int maxSortOrder(Long restaurantId);

}
