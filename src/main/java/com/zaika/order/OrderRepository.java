package com.zaika.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/** Uses {@code idx_orders_customer_status}. */
	boolean existsByCustomerIdAndStatusIn(long customerId, Collection<OrderStatus> statuses);

	/** The order a checkout form already placed (uses {@code uk_orders_customer_idempotency_key}). */
	Optional<Order> findByCustomerIdAndIdempotencyKey(long customerId, String idempotencyKey);

	/** A page of the customer's orders, newest first, restaurant loaded (idx_orders_customer_created). */
	@EntityGraph(attributePaths = "restaurant")
	Page<Order> findByCustomerIdOrderByCreatedAtDescIdDesc(long customerId, Pageable pageable);

	/** Loads the dishes of these orders in one query (fills their item lists in the current persistence context). */
	@Query("select o from Order o left join fetch o.items where o.id in :ids")
	List<Order> fetchItems(Collection<Long> ids);

	/** The customer's order with its restaurant and dishes; someone else's code finds nothing. */
	@EntityGraph(attributePaths = { "restaurant", "items" })
	Optional<Order> findByCodeAndCustomerId(String code, long customerId);

	/** The order with a row lock, so paying, cancelling and expiring it never run at the same time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.code = :code")
	Optional<Order> findForUpdateByCode(String code);

	/** The same, by id. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.id = :id")
	Optional<Order> findForUpdate(long id);

	/** Ids of orders in {@code status} created at or before {@code cutoff}, oldest first (idx_orders_status_created). */
	@Query("select o.id from Order o where o.status = :status and o.createdAt <= :cutoff order by o.createdAt, o.id")
	List<Long> findIdsByStatusCreatedUpTo(OrderStatus status, LocalDateTime cutoff);

}
