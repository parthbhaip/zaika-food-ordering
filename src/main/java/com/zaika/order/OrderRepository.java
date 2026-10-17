package com.zaika.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
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

	/** Orders of a restaurant in any of {@code statuses}, e.g. {@link OrderStatus#PLACED_AND_LIVE} (admin review). */
	long countByRestaurantIdAndStatusIn(long restaurantId, Collection<OrderStatus> statuses);

	/** The restaurant's five latest placed orders, newest first (idx_orders_restaurant_placed); dishes not loaded. */
	List<Order> findTop5ByRestaurantIdAndPlacedAtNotNullOrderByPlacedAtDescIdDesc(long restaurantId);

	/**
	 * The restaurant's orders in {@code statuses} with their customer, rider and dishes, oldest placed first, in one
	 * query (idx_orders_restaurant_status_created): the owner's live orders board.
	 */
	@EntityGraph(attributePaths = { "customer", "rider", "items" })
	List<Order> findByRestaurantIdAndStatusInOrderByPlacedAtAscIdAsc(long restaurantId,
			Collection<OrderStatus> statuses);

	/** One order of the owner's restaurant with everything its kitchen ticket prints; another kitchen's finds nothing. */
	@EntityGraph(attributePaths = { "restaurant", "customer", "rider", "items" })
	Optional<Order> findTicketByCodeAndRestaurantOwnerId(String code, long ownerId);

	/**
	 * A page of the restaurant's orders in {@code statuses} placed in [{@code from}, {@code to}), newest first, with
	 * their customer (idx_orders_restaurant_placed): the owner's report table. Dishes not loaded.
	 */
	@EntityGraph(attributePaths = "customer")
	@Query(value = """
			select o from Order o where o.restaurant.id = :restaurantId and o.status in :statuses
			and o.placedAt >= :from and o.placedAt < :to order by o.placedAt desc, o.id desc""", countQuery = """
			select count(o) from Order o where o.restaurant.id = :restaurantId and o.status in :statuses
			and o.placedAt >= :from and o.placedAt < :to""")
	Page<Order> findReport(long restaurantId, Collection<OrderStatus> statuses, LocalDateTime from, LocalDateTime to,
			Pageable pageable);

	/** Every order of {@link #findReport} with customer and dishes, in one query: the owner's CSV export. */
	@EntityGraph(attributePaths = { "customer", "items" })
	@Query("""
			select o from Order o where o.restaurant.id = :restaurantId and o.status in :statuses
			and o.placedAt >= :from and o.placedAt < :to order by o.placedAt desc, o.id desc""")
	List<Order> findReportRows(long restaurantId, Collection<OrderStatus> statuses, LocalDateTime from,
			LocalDateTime to);

	/** How many orders of one status a restaurant had, and their food value. */
	record StatusTotal(OrderStatus status, Long orders, BigDecimal itemTotal) {
	}

	/** The restaurant's orders placed in [{@code from}, {@code to}), counted per status. */
	@Query("""
			select new com.zaika.order.OrderRepository$StatusTotal(o.status, count(o), sum(o.itemTotal)) from Order o
			where o.restaurant.id = :restaurantId and o.placedAt >= :from and o.placedAt < :to group by o.status""")
	List<StatusTotal> totalsByStatus(long restaurantId, LocalDateTime from, LocalDateTime to);

	/** A dish as sold: how many and for how much. */
	record DishSales(String name, Boolean veg, Long quantity, BigDecimal sales) {
	}

	/**
	 * The restaurant's best-selling dishes (by quantity, then value) in orders in {@code statuses} placed in
	 * [{@code from}, {@code to}). Dishes are grouped by the name on the order.
	 */
	@Query("""
			select new com.zaika.order.OrderRepository$DishSales(i.name, i.veg, sum(i.quantity), sum(i.unitPrice * i.quantity))
			from Order o join o.items i
			where o.restaurant.id = :restaurantId and o.status in :statuses and o.placedAt >= :from and o.placedAt < :to
			group by i.name, i.veg order by sum(i.quantity) desc, sum(i.unitPrice * i.quantity) desc, i.name""")
	List<DishSales> findTopDishes(long restaurantId, Collection<OrderStatus> statuses, LocalDateTime from,
			LocalDateTime to, Pageable pageable);

	/** Ids of orders in {@code status} created at or before {@code cutoff}, oldest first (idx_orders_status_created). */
	@Query("select o.id from Order o where o.status = :status and o.createdAt <= :cutoff order by o.createdAt, o.id")
	List<Long> findIdsByStatusCreatedUpTo(OrderStatus status, LocalDateTime cutoff);

	/** The order by its code with its restaurant, no lock: a rider's claim relies on the {@code version} check. */
	@EntityGraph(attributePaths = "restaurant")
	Optional<Order> findByCode(String code);

	/**
	 * The oldest 20 orders in {@code statuses} that no rider has taken yet, with their restaurant: the rider panel's
	 * "available orders" (idx_orders_status_created). Dishes not loaded.
	 */
	@EntityGraph(attributePaths = "restaurant")
	List<Order> findTop20ByRiderIsNullAndStatusInOrderByPlacedAtAscIdAsc(Collection<OrderStatus> statuses);

	/** The rider's orders in {@code statuses} with restaurant, customer and dishes (idx_orders_rider_status). */
	@EntityGraph(attributePaths = { "restaurant", "customer", "items" })
	List<Order> findByRiderIdAndStatusIn(long riderId, Collection<OrderStatus> statuses);

	/** Uses {@code idx_orders_rider_status}. */
	boolean existsByRiderIdAndStatusIn(long riderId, Collection<OrderStatus> statuses);

	/** Platform-wide orders of one day (by placed time): how many and their grand total (GMV). */
	record DayTotal(LocalDate day, Long orders, BigDecimal grandTotal) {
	}

	/** Orders in {@code statuses} placed at or after {@code from}, per day, across all restaurants (idx_orders_placed). */
	@Query("""
			select new com.zaika.order.OrderRepository$DayTotal(cast(o.placedAt as LocalDate), count(o), sum(o.grandTotal))
			from Order o where o.placedAt >= :from and o.status in :statuses group by cast(o.placedAt as LocalDate)""")
	List<DayTotal> dailyTotals(LocalDateTime from, Collection<OrderStatus> statuses);

	/** A restaurant's sales over a period: orders and food value (item total). */
	record RestaurantSales(Long restaurantId, String name, Long orders, BigDecimal itemTotal) {
	}

	/**
	 * The restaurants with the highest food value in orders in {@code statuses} placed at or after {@code from}, best
	 * first (ties by name) (idx_orders_placed).
	 */
	@Query("""
			select new com.zaika.order.OrderRepository$RestaurantSales(r.id, r.name, count(o), sum(o.itemTotal))
			from Order o join o.restaurant r where o.placedAt >= :from and o.status in :statuses
			group by r.id, r.name order by sum(o.itemTotal) desc, r.name, r.id""")
	List<RestaurantSales> findTopRestaurants(LocalDateTime from, Collection<OrderStatus> statuses, Limit limit);

	/**
	 * The admin's order list: a page of every order, newest checkout first, with restaurant and customer. Each filter
	 * is optional (null = any): the code contains {@code codePattern} (lower-case LIKE pattern, {@code !} escapes), one
	 * status, one restaurant, and checkout time in [{@code from}, {@code to}) (idx_orders_created,
	 * idx_orders_status_created, idx_orders_restaurant_status_created). Dishes not loaded.
	 */
	@EntityGraph(attributePaths = { "restaurant", "customer" })
	@Query(value = """
			select o from Order o where (:codePattern is null or lower(o.code) like :codePattern escape '!')
			and (:status is null or o.status = :status) and (:restaurantId is null or o.restaurant.id = :restaurantId)
			and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to)
			order by o.createdAt desc, o.id desc""", countQuery = """
			select count(o) from Order o where (:codePattern is null or lower(o.code) like :codePattern escape '!')
			and (:status is null or o.status = :status) and (:restaurantId is null or o.restaurant.id = :restaurantId)
			and (:from is null or o.createdAt >= :from) and (:to is null or o.createdAt < :to)""")
	Page<Order> findForAdmin(String codePattern, OrderStatus status, Long restaurantId, LocalDateTime from,
			LocalDateTime to, Pageable pageable);

	/** Any order by its code with restaurant (and owner), customer, rider and dishes in one query: the admin's view. */
	@EntityGraph(attributePaths = { "restaurant", "restaurant.owner", "customer", "rider", "items" })
	Optional<Order> findDetailByCode(String code);

	/** The ten latest placed orders on the platform, newest first, with restaurant and customer (idx_orders_placed). */
	@EntityGraph(attributePaths = { "restaurant", "customer" })
	List<Order> findTop10ByPlacedAtNotNullOrderByPlacedAtDescIdDesc();

}
