package com.zaika.order;

import java.math.BigDecimal;
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

	/** Whether the dish is on any order, whatever its status (idx_order_items_menu_item). Such dishes are archived. */
	@Query("select count(i) > 0 from OrderItem i where i.menuItem.id = :menuItemId")
	boolean wasEverOrdered(long menuItemId);

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

	/** The customer's order for its tracking page: restaurant, rider (if any) and dishes in one query. */
	@EntityGraph(attributePaths = { "restaurant", "rider", "items" })
	Optional<Order> findTrackedByCodeAndCustomerId(String code, long customerId);

	/** The order with a row lock, so paying, cancelling and expiring it never run at the same time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.code = :code")
	Optional<Order> findForUpdateByCode(String code);

	/** The same, by id. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Order o where o.id = :id")
	Optional<Order> findForUpdate(long id);

	/** A restaurant's sale: when the order was placed and its food value (item total). */
	record Sale(LocalDateTime placedAt, BigDecimal itemTotal) {
	}

	/** The restaurant's orders in {@code statuses} placed at or after {@code from} (idx_orders_restaurant_placed). */
	@Query("""
			select new com.zaika.order.OrderRepository$Sale(o.placedAt, o.itemTotal) from Order o
			where o.restaurant.id = :restaurantId and o.placedAt >= :from and o.status in :statuses""")
	List<Sale> findSales(long restaurantId, LocalDateTime from, Collection<OrderStatus> statuses);

	/** Uses {@code idx_orders_restaurant_status_created}. */
	long countByRestaurantIdAndStatus(long restaurantId, OrderStatus status);

	/** The restaurant's five latest placed orders, newest first (idx_orders_restaurant_placed); dishes not loaded. */
	List<Order> findTop5ByRestaurantIdAndPlacedAtNotNullOrderByPlacedAtDescIdDesc(long restaurantId);

	/** Ids of orders in {@code status} created at or before {@code cutoff}, oldest first (idx_orders_status_created). */
	@Query("select o.id from Order o where o.status = :status and o.createdAt <= :cutoff order by o.createdAt, o.id")
	List<Long> findIdsByStatusCreatedUpTo(OrderStatus status, LocalDateTime cutoff);

}
