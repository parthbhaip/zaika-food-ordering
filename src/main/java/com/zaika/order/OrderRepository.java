package com.zaika.order;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/** Uses {@code idx_orders_customer_status}. */
	boolean existsByCustomerIdAndStatusIn(long customerId, Collection<OrderStatus> statuses);

	/** The order a checkout form already placed (uses {@code uk_orders_customer_idempotency_key}). */
	Optional<Order> findByCustomerIdAndIdempotencyKey(long customerId, String idempotencyKey);

}
