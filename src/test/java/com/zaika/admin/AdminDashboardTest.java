package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.admin.AdminDashboardService.Dashboard;
import com.zaika.admin.AdminDashboardService.Kpis;
import com.zaika.admin.AdminDashboardService.TopRestaurant;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Formatters;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.owner.RevenueChart.Bar;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T38: the admin dashboard ({@code /admin}). The numbers are platform-wide and every test in this class adds rows to
 * the same database, so the counting tests compare the dashboard before and after their own rows. Orders go through
 * the real workflow at chosen clock times; the clock otherwise stands at Sunday 4 Oct 2026, 7:30 PM. Only the
 * top-restaurants test sells dishes worth ₹10,000 or more, so its kitchens always lead that ranking.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:admindash;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminDashboardTest {

	static final LocalDate TODAY = MutableClock.DEFAULT_NOW.toLocalDate();

	@Autowired
	MockMvc mvc;

	@Autowired
	AdminDashboardService service;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	MutableClock clock;

	@Autowired
	Formatters fmt;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** A kitchen with one dish at {@code price}. The fixture's delivery fee is ₹30. */
	MenuItem kitchen(String name, String price) {
		return kitchen(name, price, RestaurantStatus.APPROVED);
	}

	MenuItem kitchen(String name, String price, RestaurantStatus status) {
		String slug = "adm-" + UUID.randomUUID().toString().substring(0, 8);
		Restaurant r = fixtures.restaurant(slug, name).status(status).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		return items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal(price), false));
	}

	/** {@code quantity} x the dish, ordered by {@code customer} at {@code at} and walked to {@code target}. */
	Order orderAt(LocalDateTime at, User customer, MenuItem dish, int quantity, OrderStatus target) {
		clock.setTo(at);
		Order order = orders.place(customer.getId(), dish.getId(), quantity, PaymentMethod.COD, target);
		clock.reset();
		return order;
	}

	Order orderAt(LocalDateTime at, User customer, MenuItem dish, OrderStatus target) {
		return orderAt(at, customer, dish, 1, target);
	}

	User admin() {
		return users.save(new User("Aditi Kulkarni", "admin-" + UUID.randomUUID() + "@zaika.test", "9890011223",
				"hash", Role.ADMIN));
	}

	User rider(RiderStatus status, boolean online) {
		User rider = orders.rider();
		DeliveryPartnerProfile profile = new DeliveryPartnerProfile(rider, VehicleType.BIKE, "MH09AB1234",
				"Rajarampuri");
		profile.changeStatus(status, status == RiderStatus.SUSPENDED ? "Three late deliveries" : null);
		profile.setOnline(online);
		profiles.save(profile);
		return rider;
	}

	RequestPostProcessor as(User user) {
		return user(userDetails.loadUserByUsername(user.getEmail()));
	}

	String page() throws Exception {
		return page(admin());
	}

	String page(User admin) throws Exception {
		return mvc.perform(get("/admin").with(as(admin)))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/dashboard"))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	static LocalDateTime daysAgo(int days, int hour) {
		return TODAY.minusDays(days).atTime(hour, 0);
	}

	// ---------------------------------------------------------------- KPIs

	@Test
	void kpisCountTodaysLiveOrdersGmvAndThePlatformsAccounts() {
		Kpis before = service.dashboard().kpis();

		MenuItem thali = kitchen("Kpi Kitchen", "200"); // ₹200 + ₹30 delivery + ₹5 platform fee + 5% GST on food = ₹245
		kitchen("Kpi Pending Kitchen", "150", RestaurantStatus.PENDING);
		kitchen("Kpi Suspended Kitchen", "150", RestaurantStatus.SUSPENDED);
		User customer = orders.customer();
		User disabled = orders.customer();
		disabled.setEnabled(false);
		users.save(disabled);
		rider(RiderStatus.APPROVED, true);
		rider(RiderStatus.APPROVED, false);
		rider(RiderStatus.PENDING, false);
		rider(RiderStatus.SUSPENDED, false);

		Order placed = orderAt(daysAgo(0, 9), customer, thali, OrderStatus.PLACED);
		orderAt(daysAgo(0, 10), customer, thali, OrderStatus.DELIVERED);
		orderAt(daysAgo(0, 0), customer, thali, OrderStatus.READY_FOR_PICKUP); // midnight counts as today
		orderAt(daysAgo(0, 11), customer, thali, OrderStatus.REJECTED); // never counts
		orderAt(daysAgo(0, 12), customer, thali, OrderStatus.PENDING_PAYMENT); // unpaid, never placed
		orders.placeThenCancel(customer.getId(), thali.getId(), 1, PaymentMethod.COD); // cancelled today
		orderAt(daysAgo(1, 23).plusMinutes(59), customer, thali, OrderStatus.DELIVERED); // yesterday 11:59 PM
		orderAt(daysAgo(6, 12), customer, thali, OrderStatus.DELIVERED); // first day of the 7
		orderAt(daysAgo(7, 12), customer, thali, OrderStatus.DELIVERED); // just outside the 7 days

		Kpis after = service.dashboard().kpis();
		BigDecimal bill = placed.getBill().grandTotal();
		assertThat(bill).isEqualByComparingTo("245.00");
		assertThat(after.ordersToday() - before.ordersToday()).isEqualTo(3);
		assertThat(after.gmvToday().subtract(before.gmvToday())).isEqualByComparingTo(bill.multiply(BigDecimal.valueOf(3)));
		assertThat(after.gmvWeek().subtract(before.gmvWeek())).isEqualByComparingTo(bill.multiply(BigDecimal.valueOf(5)));
		assertThat(after.gmvToday().scale()).isEqualTo(2);
		assertThat(after.activeRestaurants() - before.activeRestaurants()).isEqualTo(1);
		assertThat(after.pendingRestaurants() - before.pendingRestaurants()).isEqualTo(1);
		// the order fixtures' delivered orders create rider ACCOUNTS without profiles: those are not counted
		assertThat(after.ridersOnline() - before.ridersOnline()).isEqualTo(1);
		assertThat(after.pendingRiders() - before.pendingRiders()).isEqualTo(1);
		assertThat(after.pendingApprovals() - before.pendingApprovals()).isEqualTo(2);
		assertThat(after.customers() - before.customers()).isEqualTo(1); // the disabled one does not count
	}

	@Test
	void thePageShowsTheKpisFormattedAndFlagsPendingApprovals() throws Exception {
		MenuItem thali = kitchen("Kpi Page Kitchen", "200");
		kitchen("Kpi Page Pending", "150", RestaurantStatus.PENDING);
		orderAt(daysAgo(0, 8), orders.customer(), thali, OrderStatus.ACCEPTED);
		Kpis k = service.dashboard().kpis();

		String html = page();
		assertThat(html).contains("<title>Admin dashboard | Zaika</title>")
			.contains("Platform overview")
			.contains("Kolhapur · Sunday 4 Oct")
			.contains("<dt>Orders today</dt> <dd class=\"owner-stat-value\">" + fmt.count(k.ordersToday()) + "</dd>")
			.contains("<dt>GMV today</dt> <dd class=\"owner-stat-value\">" + fmt.price(k.gmvToday()) + "</dd>")
			.contains("<dt>GMV, last 7 days</dt> <dd class=\"owner-stat-value\">" + fmt.price(k.gmvWeek()) + "</dd>")
			.contains("<dt>Riders online</dt> <dd class=\"owner-stat-value\">" + fmt.count(k.ridersOnline()) + "</dd>")
			.contains("<dt>Active restaurants</dt> <dd class=\"owner-stat-value\">" + fmt.count(k.activeRestaurants()) + "</dd>")
			.contains("<dt>Customers</dt> <dd class=\"owner-stat-value\">" + fmt.count(k.customers()) + "</dd>")
			.contains("<div class=\"card owner-stat is-waiting\"> <dt>Pending approvals</dt> <dd class=\"owner-stat-value\">"
					+ fmt.count(k.pendingApprovals()) + "</dd>")
			.contains(k.pendingRestaurants() + " restaurant")
			.contains("GMV is what customers paid");
		assertThat(k.pendingApprovals()).isPositive();
	}

	// ---------------------------------------------------------------- orders per day

	@Test
	void theOrdersChartCountsLiveOrdersForEachOfTheLast14Days() throws Exception {
		List<Bar> before = service.dashboard().ordersChart().bars();
		MenuItem thali = kitchen("Chart Kitchen", "120");
		User customer = orders.customer();
		orderAt(daysAgo(13, 12), customer, thali, OrderStatus.DELIVERED); // first bar
		orderAt(daysAgo(13, 13), customer, thali, OrderStatus.DELIVERED);
		orderAt(daysAgo(14, 12), customer, thali, OrderStatus.DELIVERED); // too old
		orderAt(daysAgo(5, 12), customer, thali, OrderStatus.REJECTED); // never counts
		orderAt(daysAgo(0, 9), customer, thali, OrderStatus.PREPARING);

		Dashboard d = service.dashboard();
		List<Bar> after = d.ordersChart().bars();
		assertThat(after).hasSize(14);
		assertThat(after.get(0).day()).isEqualTo(TODAY.minusDays(13));
		assertThat(after.get(13).day()).isEqualTo(TODAY);
		assertThat(after.get(13).today()).isTrue();
		List<Integer> added = new ArrayList<>();
		for (int i = 0; i < 14; i++) {
			added.add(after.get(i).amount().subtract(before.get(i).amount()).intValueExact());
		}
		assertThat(added).containsExactly(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
		// every count is a whole number and the scale's half tick is one too
		assertThat(d.ordersChart().ticks().get(1).amount().stripTrailingZeros().scale()).isLessThanOrEqualTo(0);

		String html = page();
		assertThat(html).contains("Orders per day")
			.contains("21 Sep to 4 Oct")
			.contains("<caption>Orders per day, last 14 days</caption>")
			.contains("<th scope=\"row\">Monday 21 Sep</th><td>" + fmt.count(after.get(0).amount().longValue()) + "</td>")
			.contains("<th scope=\"row\">Today, Sunday 4 Oct</th>")
			.contains("class=\"revenue-chart-bar is-today\"")
			.contains("title=\"Monday 21 Sep: " + fmt.count(after.get(0).amount().longValue()) + " orders\"");
	}

	// ---------------------------------------------------------------- top restaurants

	@Test
	void theTopFiveRestaurantsAreRankedByFoodSalesOverTheLast30Days() throws Exception {
		User customer = orders.customer();
		String[] names = { "Top Alpha", "Top Bravo", "Top Charlie", "Top Delta", "Top <Echo>", "Top Foxtrot" };
		int[] prices = { 60000, 50000, 40000, 30000, 10000, 10000 };
		for (int i = 0; i < names.length; i++) {
			MenuItem dish = kitchen(names[i], String.valueOf(prices[i]));
			orderAt(daysAgo(29, 12), customer, dish, OrderStatus.DELIVERED); // first day of the 30
			if (i == 4) {
				orderAt(daysAgo(2, 12), customer, dish, OrderStatus.DELIVERED); // Echo: 2 orders, ₹20,000
			}
		}
		MenuItem old = kitchen("Top Too Old", "90000");
		orderAt(daysAgo(30, 12), customer, old, OrderStatus.DELIVERED);
		MenuItem rejected = kitchen("Top Rejected", "95000");
		orderAt(daysAgo(1, 12), customer, rejected, OrderStatus.REJECTED);
		MenuItem unpaid = kitchen("Top Unpaid", "99000");
		orderAt(daysAgo(1, 12), customer, unpaid, OrderStatus.PENDING_PAYMENT);

		List<TopRestaurant> top = service.dashboard().top();
		assertThat(top).extracting(TopRestaurant::name)
			.containsExactly("Top Alpha", "Top Bravo", "Top Charlie", "Top Delta", "Top <Echo>");
		assertThat(top).extracting(TopRestaurant::sales)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("60000"), new BigDecimal("50000"), new BigDecimal("40000"),
					new BigDecimal("30000"), new BigDecimal("20000"));
		assertThat(top).extracting(TopRestaurant::orders).containsExactly(1L, 1L, 1L, 1L, 2L);
		assertThat(top).extracting(TopRestaurant::percent).containsExactly(100.0, 83.3, 66.7, 50.0, 33.3);

		String page = page();
		// only the ranking card: the recent orders table below may list these kitchens too
		String html = page.substring(page.indexOf("id=\"admin-top-title\""), page.indexOf("id=\"admin-recent-title\""));
		assertThat(html).contains("Top restaurants")
			.contains("Food sales, last 30 days")
			.contains("<span class=\"admin-top-name\">Top &lt;Echo&gt;</span> <strong class=\"admin-top-sales\">₹20,000</strong>")
			.contains("<rect class=\"admin-top-fill\" width=\"83.3\" height=\"8\"></rect>")
			.contains("2 orders")
			.doesNotContain("Top <Echo>", "Top Foxtrot", "Top Too Old", "Top Rejected", "Top Unpaid");
		assertThat(html.indexOf("Top Alpha")).isLessThan(html.indexOf("Top Bravo"));
		assertThat(html.indexOf("Top Delta")).isLessThan(html.indexOf("Top &lt;Echo&gt;"));
	}

	@Test
	void topRestaurantBarsAreScaledToTheBestAndNeverVanish() {
		var rows = List.of(new com.zaika.order.OrderRepository.RestaurantSales(1L, "Big", 9L, new BigDecimal("100000")),
				new com.zaika.order.OrderRepository.RestaurantSales(2L, "Tiny", 1L, new BigDecimal("150.50")));
		List<TopRestaurant> top = TopRestaurant.of(rows);
		assertThat(top.get(0).percent()).isEqualTo(100.0);
		assertThat(top.get(1).percent()).isEqualTo(TopRestaurant.MIN_PERCENT);
		assertThat(top.get(1).sales()).isEqualByComparingTo("150.50");
		assertThat(TopRestaurant.of(List.of())).isEmpty();
	}

	// ---------------------------------------------------------------- recent orders

	@Test
	void recentOrdersShowTheTenLatestPlacedOrdersNewestFirst() throws Exception {
		MenuItem thali = kitchen("Recent <b>Kitchen</b>", "180");
		User customer = orders.customer();
		List<Order> placed = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			placed.add(orderAt(daysAgo(0, 20).plusMinutes(i), customer, thali,
					i == 10 ? OrderStatus.REJECTED : OrderStatus.PLACED));
		}
		Order unpaid = orderAt(daysAgo(0, 20).plusMinutes(30), customer, thali, OrderStatus.PENDING_PAYMENT);

		clock.setTo(daysAgo(0, 21).plusMinutes(10));
		String html = page();
		List<String> expected = placed.reversed().subList(0, 10).stream().map(Order::getCode).toList();
		int last = -1;
		for (String code : expected) {
			int at = html.indexOf("<th scope=\"row\"><a class=\"admin-code\" href=\"/admin/orders/" + code + "\">" + code
					+ "</a></th>");
			assertThat(at).as(code).isGreaterThan(last);
			last = at;
		}
		assertThat(html).doesNotContain(placed.get(0).getCode()) // the 11th newest
			.doesNotContain(unpaid.getCode())
			.contains("Recent orders")
			.contains("<td>Recent &lt;b&gt;Kitchen&lt;/b&gt;</td>")
			.doesNotContain("Recent <b>Kitchen</b>")
			.contains("<td>Riya Shinde</td>")
			.contains("<span class=\"badge badge-danger\">Rejected</span>")
			.contains("<span class=\"badge badge-primary\">Placed</span>")
			.contains("<td class=\"num\">" + fmt.price(placed.get(10).getBill().grandTotal()) + "</td>")
			.contains("title=\"" + fmt.dateTime(daysAgo(0, 20).plusMinutes(10)) + "\">1 hour ago</time>")
			// the admin dashboard never shows phone numbers
			.doesNotContain(customer.getPhone(), "98765 43210");
	}

	// ---------------------------------------------------------------- queries and access

	@Test
	void thePageRunsAFixedNumberOfQueries() throws Exception {
		MenuItem thali = kitchen("Query Kitchen", "150");
		User customer = orders.customer();
		orderAt(daysAgo(0, 9), customer, thali, OrderStatus.DELIVERED);
		User admin = admin();
		RequestPostProcessor signedIn = as(admin);
		mvc.perform(get("/admin").with(signedIn)).andExpect(status().isOk()); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		mvc.perform(get("/admin").with(signedIn)).andExpect(status().isOk());
		long before = stats.getPrepareStatementCount();
		for (int i = 0; i < 6; i++) {
			MenuItem dish = kitchen("Query Kitchen " + i, "150");
			orderAt(daysAgo(i, 10), orders.customer(), dish, OrderStatus.DELIVERED);
		}
		stats.clear();
		mvc.perform(get("/admin").with(signedIn)).andExpect(status().isOk());
		long after = stats.getPrepareStatementCount();
		// daily totals, 2 restaurant counts, customers, 2 rider counts, top five, recent ten, the header bell's unread
		// count: none per order
		assertThat(after).isEqualTo(before);
		assertThat(after).isLessThanOrEqualTo(9);
	}

	@Test
	void onlyAdminsGetTheDashboard() throws Exception {
		mvc.perform(get("/admin")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		MenuItem thali = kitchen("Guard Kitchen", "150");
		long ownerId = items.findWithRestaurantById(thali.getId()).orElseThrow().getRestaurant().getOwner().getId();
		User owner = users.findById(ownerId).orElseThrow();
		for (User notAdmin : List.of(orders.customer(), owner, rider(RiderStatus.APPROVED, false))) {
			mvc.perform(get("/admin").with(as(notAdmin))).andExpect(status().isForbidden());
		}
		String html = page();
		// the header's account menu links the admin areas
		assertThat(html).contains("href=\"/admin\"");
	}

}
