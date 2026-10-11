package com.zaika.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderRepository.DayTotal;
import com.zaika.order.OrderRepository.RestaurantSales;
import com.zaika.order.OrderStatus;
import com.zaika.owner.RevenueChart;
import com.zaika.user.Role;
import com.zaika.user.UserRepository;

/**
 * The admin dashboard's platform numbers. Orders count by the time they were placed (Asia/Kolkata, from the injected
 * Clock) and only when placed and not cancelled or rejected ({@link OrderStatus#PLACED_AND_LIVE}), as on the owner
 * dashboard. GMV is what customers paid (grand total, after coupons, with fees and GST); a restaurant's sales are its
 * food value (item total), the figure its owner sees.
 */
@Service
public class AdminDashboardService {

	/** Days in the orders chart, today included. */
	public static final int CHART_DAYS = 14;

	/** Days the top-restaurants ranking covers, today included. */
	public static final int TOP_DAYS = 30;

	static final int TOP_COUNT = 5;

	/** Lowest top of the orders chart's scale, so its half tick is always a whole number of orders. */
	static final BigDecimal CHART_MIN_SCALE = BigDecimal.TEN;

	private final OrderRepository orders;

	private final RestaurantRepository restaurants;

	private final UserRepository users;

	private final DeliveryPartnerProfileRepository riders;

	private final Clock clock;

	AdminDashboardService(OrderRepository orders, RestaurantRepository restaurants, UserRepository users,
			DeliveryPartnerProfileRepository riders, Clock clock) {
		this.orders = orders;
		this.restaurants = restaurants;
		this.users = users;
		this.riders = riders;
		this.clock = clock;
	}

	/** Everything on the dashboard in a fixed number of queries, whatever the volume. */
	@Transactional(readOnly = true)
	public Dashboard dashboard() {
		LocalDate today = LocalDate.now(clock);
		Map<LocalDate, BigDecimal> ordersByDay = new HashMap<>();
		long ordersToday = 0;
		BigDecimal gmvToday = BigDecimal.ZERO;
		BigDecimal gmvWeek = BigDecimal.ZERO;
		for (DayTotal day : orders.dailyTotals(today.minusDays(CHART_DAYS - 1).atStartOfDay(),
				OrderStatus.PLACED_AND_LIVE)) {
			ordersByDay.put(day.day(), BigDecimal.valueOf(day.orders()));
			if (day.day().equals(today)) {
				ordersToday = day.orders();
				gmvToday = day.grandTotal();
			}
			if (day.day().isAfter(today.minusDays(7))) {
				gmvWeek = gmvWeek.add(day.grandTotal());
			}
		}
		Kpis kpis = new Kpis(ordersToday, money(gmvToday), money(gmvWeek),
				restaurants.countByStatus(RestaurantStatus.APPROVED), users.countByRoleAndEnabledTrue(Role.CUSTOMER),
				riders.countByStatusAndOnline(RiderStatus.APPROVED, true),
				restaurants.countByStatus(RestaurantStatus.PENDING), riders.countByStatus(RiderStatus.PENDING));
		List<RestaurantSales> top = orders.findTopRestaurants(today.minusDays(TOP_DAYS - 1).atStartOfDay(),
				OrderStatus.PLACED_AND_LIVE, Limit.of(TOP_COUNT));
		return new Dashboard(today, kpis, RevenueChart.of(today, CHART_DAYS, CHART_MIN_SCALE, ordersByDay),
				TopRestaurant.of(top), orders.findTop10ByPlacedAtNotNullOrderByPlacedAtDescIdDesc());
	}

	private static BigDecimal money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Everything on the dashboard. {@code ordersChart} holds orders per day as amounts (whole numbers);
	 * {@code recent} has restaurant and customer loaded.
	 */
	public record Dashboard(LocalDate today, Kpis kpis, RevenueChart ordersChart, List<TopRestaurant> top,
			List<Order> recent) {
	}

	/** The headline numbers. GMV in rupees (2dp); the rest are counts. */
	public record Kpis(long ordersToday, BigDecimal gmvToday, BigDecimal gmvWeek, long activeRestaurants,
			long customers, long ridersOnline, long pendingRestaurants, long pendingRiders) {

		/** Restaurants and riders waiting for review. */
		public long pendingApprovals() {
			return pendingRestaurants + pendingRiders;
		}

	}

	/**
	 * One row of the top-restaurants chart: its sales and how long its bar is (0-100, the best restaurant is 100; a
	 * restaurant that sold anything gets at least {@link #MIN_PERCENT} so its bar stays visible).
	 */
	public record TopRestaurant(String name, long orders, BigDecimal sales, double percent) {

		static final double MIN_PERCENT = 2;

		static List<TopRestaurant> of(List<RestaurantSales> rows) {
			BigDecimal best = rows.stream().map(RestaurantSales::itemTotal).reduce(BigDecimal.ZERO, BigDecimal::max);
			return rows.stream().map(r -> {
				double percent = best.signum() == 0 || r.itemTotal().signum() == 0 ? 0
						: Math.max(MIN_PERCENT, r.itemTotal()
							.multiply(BigDecimal.valueOf(100))
							.divide(best, 1, RoundingMode.HALF_UP)
							.doubleValue());
				return new TopRestaurant(r.name(), r.orders(), money(r.itemTotal()), percent);
			}).toList();
		}

	}

}
