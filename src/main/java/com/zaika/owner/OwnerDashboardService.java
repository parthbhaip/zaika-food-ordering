package com.zaika.owner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.common.NotFoundException;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderRepository.Sale;
import com.zaika.order.OrderStatus;

/**
 * What the owner panel shows. Everything is looked up by the signed-in owner's id, never by a URL parameter, so an
 * owner only ever sees and changes their own restaurant.
 * <p>
 * Sales figures count orders by the time they were placed (Asia/Kolkata, from the injected Clock) and only orders
 * that are placed and not cancelled or rejected ({@link OrderStatus#PLACED_AND_LIVE}); revenue is the food value
 * (item total, before coupons, fees and GST).
 */
@Service
public class OwnerDashboardService {

	private final RestaurantRepository restaurants;

	private final OrderRepository orders;

	private final Clock clock;

	OwnerDashboardService(RestaurantRepository restaurants, OrderRepository orders, Clock clock) {
		this.restaurants = restaurants;
		this.orders = orders;
		this.clock = clock;
	}

	/**
	 * The dashboard of the owner's restaurant (empty if this owner has none): today's numbers, the orders waiting to be
	 * accepted, the five latest orders with their dishes and the 7-day revenue chart. Five queries.
	 */
	@Transactional(readOnly = true)
	public Optional<Dashboard> dashboard(long ownerId) {
		return restaurants.findWithCuisinesByOwnerId(ownerId).map(restaurant -> {
			LocalDate today = LocalDate.now(clock);
			Map<LocalDate, BigDecimal> byDay = new HashMap<>();
			long todayOrders = 0;
			for (Sale sale : orders.findSales(restaurant.getId(), today.minusDays(RevenueChart.DAYS - 1).atStartOfDay(),
					OrderStatus.PLACED_AND_LIVE)) {
				LocalDate day = sale.placedAt().toLocalDate();
				byDay.merge(day, sale.itemTotal(), BigDecimal::add);
				if (day.equals(today)) {
					todayOrders++;
				}
			}
			Today todays = new Today(todayOrders, byDay.getOrDefault(today, BigDecimal.ZERO).setScale(2));
			List<Order> latest = orders.findTop5ByRestaurantIdAndPlacedAtNotNullOrderByPlacedAtDescIdDesc(restaurant.getId());
			if (!latest.isEmpty()) {
				orders.fetchItems(latest.stream().map(Order::getId).toList());
			}
			long pending = orders.countByRestaurantIdAndStatus(restaurant.getId(), OrderStatus.PLACED);
			return new Dashboard(restaurant, AcceptingOrders.of(restaurant, clock), todays, pending, latest,
					RevenueChart.of(today, byDay));
		});
	}

	/**
	 * Pauses or resumes new orders for the owner's restaurant. Orders already placed are not touched.
	 * @throws NotFoundException if this owner has no restaurant
	 */
	@Transactional
	public AcceptingOrders setAcceptingOrders(long ownerId, boolean accepting) {
		Restaurant restaurant = restaurants.findByOwnerId(ownerId)
			.orElseThrow(() -> new NotFoundException("No restaurant for owner " + ownerId));
		restaurant.setAcceptingOrders(accepting);
		return AcceptingOrders.of(restaurant, clock);
	}

	/** Everything on the dashboard. {@code latest} has its dishes loaded. */
	public record Dashboard(Restaurant restaurant, AcceptingOrders accepting, Today today, long pending,
			List<Order> latest, RevenueChart chart) {
	}

	/** Today's orders and their food value. */
	public record Today(long orders, BigDecimal revenue) {

		/** Revenue per order, to the paisa; zero without orders. */
		public BigDecimal averageOrderValue() {
			return orders == 0 ? BigDecimal.ZERO.setScale(2)
					: revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
		}

	}

	/**
	 * The accepting-orders switch: whether new orders are on, whether the kitchen is within its hours right now and,
	 * if not, when it opens.
	 */
	public record AcceptingOrders(boolean on, boolean openNow, LocalTime opensAt) {

		static AcceptingOrders of(Restaurant restaurant, Clock clock) {
			return new AcceptingOrders(restaurant.isAcceptingOrders(), restaurant.isOpenNow(clock),
					restaurant.getOpensAt());
		}

	}

}
