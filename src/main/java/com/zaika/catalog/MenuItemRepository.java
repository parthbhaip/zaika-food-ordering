package com.zaika.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

	/** Every item a customer may see (archived ones hidden, sold-out ones included), in menu order. */
	List<MenuItem> findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long restaurantId);

}
