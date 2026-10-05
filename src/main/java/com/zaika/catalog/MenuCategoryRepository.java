package com.zaika.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

	/** Menu order: sort order, then creation order. Uses idx_menu_categories_restaurant. */
	List<MenuCategory> findByRestaurantIdOrderBySortOrderAscIdAsc(Long restaurantId);

}
