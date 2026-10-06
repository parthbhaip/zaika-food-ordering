package com.zaika.order;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/** Uses {@code idx_orders_customer_status}. */
	boolean existsByCustomerIdAndStatusIn(long customerId, Collection<OrderStatus> statuses);

}
