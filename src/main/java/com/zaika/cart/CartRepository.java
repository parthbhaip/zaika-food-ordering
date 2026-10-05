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

	/**
	 * Dishes (summing quantities), their total at today's menu prices and the restaurant of the customer's cart, in
	 * one query. Always one row; the sums are null without a cart. A cart holds one restaurant's dishes only.
	 */
	@Query("""
			select new com.zaika.cart.CartSummary(sum(i.quantity), sum(i.quantity * m.price), min(r.name))
			from CartItem i join i.menuItem m join m.restaurant r
			where i.cart.user.id = :userId""")
	CartSummary summarize(long userId);

}
