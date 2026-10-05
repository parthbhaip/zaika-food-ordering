package com.zaika.cart;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CartRepository extends JpaRepository<Cart, Long> {

	/** The customer's cart with its dishes and their restaurant, in one query. */
	@Query("""
			select distinct c from Cart c
			left join fetch c.items i left join fetch i.menuItem m left join fetch m.restaurant
			where c.user.id = :userId""")
	Optional<Cart> findByUserId(long userId);

	/** How many dishes (summing quantities) are in the customer's cart; 0 without a cart. */
	@Query("select coalesce(sum(i.quantity), 0) from CartItem i where i.cart.user.id = :userId")
	long countItems(long userId);

}
