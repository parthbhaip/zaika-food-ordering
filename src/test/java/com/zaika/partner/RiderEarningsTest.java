package com.zaika.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.common.Formatters;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.RiderEarning;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.User;

/**
 * T37: the rider's earnings page ({@code /partner/earnings}): today / this week (from Monday) / this month totals, cash
 * in hand (cash collected today) and the delivery history, 20 to a page. Earnings are made through the real delivery
 * flow at chosen clock times. The clock otherwise stands at Sunday 4 Oct 2026, 7:30 PM, so this week started on
 * Monday 28 Sep and this month on Thursday 1 Oct.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:riderearnings;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RiderEarningsTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	DeliveryService deliveries;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

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

	/** A kitchen with a ₹250 thali and a ₹30 delivery fee: ₹30 + 80% of ₹30 = ₹54 per delivery. */
	record Kitchen(Restaurant restaurant, MenuItem thali) {
	}

	Kitchen kitchen(String name) {
		String slug = "earn-" + UUID.randomUUID().toString().substring(0, 8);
		Restaurant r = fixtures.restaurant(slug, name).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		return new Kitchen(r, items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false)));
	}

	User rider(RiderStatus status) {
		User rider = orders.rider();
		DeliveryPartnerProfile profile = new DeliveryPartnerProfile(rider, VehicleType.BIKE, "MH09AB1234",
				"Rajarampuri");
		profile.changeStatus(status, status == RiderStatus.SUSPENDED ? "Three late deliveries" : null);
		profile.setOnline(status == RiderStatus.APPROVED);
		profiles.save(profile);
		return rider;
	}

	/** One thali delivered by {@code rider} at {@code at}, through claim, pick-up and the customer's code. */
	RiderEarning deliverAt(Kitchen k, User rider, PaymentMethod method, LocalDateTime at) {
		clock.setTo(at);
		Order order = orders.place(orders.customer().getId(), k.thali().getId(), 1, method,
				OrderStatus.READY_FOR_PICKUP);
		deliveries.claim(rider.getId(), order.getCode());
		deliveries.pickUp(rider.getId(), order.getCode());
		RiderEarning earning = deliveries.deliver(rider.getId(), order.getCode(), order.getDeliveryOtp());
		clock.reset();
		return earning;
	}

	RequestPostProcessor as(User user) {
		return user(userDetails.loadUserByUsername(user.getEmail()));
	}

	String page(User rider, String url) throws Exception {
		return mvc.perform(get(url).with(as(rider)))
			.andExpect(status().isOk())
			.andExpect(view().name("partner/earnings"))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	static String code(RiderEarning e) {
		return e.getOrder().getCode();
	}

	// ---------------------------------------------------------------- totals and history

	@Test
	void totalsCoverTodayThisWeekFromMondayAndThisMonth() throws Exception {
		Kitchen k = kitchen("Tandoor <&> Co");
		User rider = rider(RiderStatus.APPROVED);
		RiderEarning lastMonth = deliverAt(k, rider, PaymentMethod.COD, LocalDateTime.of(2026, 9, 15, 12, 0));
		RiderEarning lastSunday = deliverAt(k, rider, PaymentMethod.ONLINE, LocalDateTime.of(2026, 9, 27, 23, 59));
		RiderEarning monday = deliverAt(k, rider, PaymentMethod.COD, LocalDateTime.of(2026, 9, 28, 0, 5));
		RiderEarning yesterday = deliverAt(k, rider, PaymentMethod.COD, LocalDateTime.of(2026, 10, 3, 23, 50));
		RiderEarning afterMidnight = deliverAt(k, rider, PaymentMethod.ONLINE, LocalDateTime.of(2026, 10, 4, 0, 10));
		RiderEarning lunch = deliverAt(k, rider, PaymentMethod.COD, LocalDateTime.of(2026, 10, 4, 12, 0));
		RiderEarning dinner = deliverAt(k, rider, PaymentMethod.COD, MutableClock.DEFAULT_NOW);
		// another rider's delivery today counts only for them
		User other = rider(RiderStatus.APPROVED);
		RiderEarning theirs = deliverAt(k, other, PaymentMethod.COD, LocalDateTime.of(2026, 10, 4, 18, 0));

		assertThat(dinner.getAmount()).isEqualByComparingTo("54.00");
		BigDecimal cashToday = lunch.getCashCollected().add(dinner.getCashCollected());
		assertThat(cashToday.signum()).isPositive();

		String html = page(rider, "/partner/earnings");
		assertThat(html).contains("<title>Earnings | Zaika</title>")
			.contains("<h1 class=\"owner-title\">Earnings</h1>")
			// today: after midnight, lunch, dinner
			.containsPattern("id=\"earnings-today\"> <dt>Today</dt> <dd class=\"owner-stat-value\">₹162</dd> "
					+ "<dd class=\"report-stat-note\">3 deliveries</dd>")
			// this week: Monday 00:05 onwards (Sunday 23:59 is last week)
			.contains("<dt>This week</dt> <dd class=\"owner-stat-value\">₹270</dd> "
					+ "<dd class=\"report-stat-note\">5 deliveries since Mon 28 Sep</dd>")
			// this month: 1 Oct onwards
			.contains("<dt>This month · October</dt> <dd class=\"owner-stat-value\">₹216</dd> "
					+ "<dd class=\"report-stat-note\">4 deliveries</dd>")
			// cash in hand: today's two cash orders only (yesterday's and Monday's cash not counted)
			.contains("id=\"earnings-cash\"")
			.contains("<dt>Cash in hand</dt> <dd class=\"owner-stat-value\">" + fmt.price(cashToday) + "</dd> "
					+ "<dd class=\"report-stat-note\">From 2 cash orders today</dd>")
			.contains("Weeks start on Monday.")
			.contains("Showing 1–7 of 7 deliveries, newest first")
			.contains("Tandoor &lt;&amp;&gt; Co")
			.doesNotContain("Tandoor <&> Co")
			.contains("₹30 + ₹24 fee share")
			.contains("<td class=\"num\">Paid online</td>")
			.contains("<td class=\"num\">" + fmt.money(dinner.getCashCollected()) + "</td>")
			.contains("<time datetime=\"2026-10-04T19:30\">4 Oct 2026, 7:30 PM</time>")
			.doesNotContain(code(theirs))
			.doesNotContain("class=\"pagination");
		assertThat(html).containsPattern("class=\"card owner-stat is-waiting\" id=\"earnings-cash\"|id=\"earnings-cash\"[^>]*is-waiting");

		// the history: newest first, every delivery including last month's
		List<RiderEarning> newestFirst = List.of(dinner, lunch, afterMidnight, yesterday, monday, lastSunday,
				lastMonth);
		int at = -1;
		for (RiderEarning e : newestFirst) {
			int next = html.indexOf("<th scope=\"row\">" + code(e) + "</th>");
			assertThat(next).as(code(e)).isGreaterThan(at);
			at = next;
		}

		// the other rider sees only their own
		String theirPage = page(other, "/partner/earnings");
		assertThat(theirPage).contains("One delivery").contains(code(theirs)).doesNotContain(code(dinner));
	}

	@Test
	void aRiderWithNoDeliveriesSeesZerosAndAnEmptyHistory() throws Exception {
		User rider = rider(RiderStatus.APPROVED);
		String html = page(rider, "/partner/earnings");
		assertThat(html).contains("<dt>Today</dt> <dd class=\"owner-stat-value\">₹0</dd> "
				+ "<dd class=\"report-stat-note\">No deliveries yet</dd>")
			.contains("0 deliveries since Mon 28 Sep")
			.contains("<dt>Cash in hand</dt> <dd class=\"owner-stat-value\">₹0</dd> "
					+ "<dd class=\"report-stat-note\">No cash orders today</dd>")
			.doesNotContain("is-waiting")
			.contains("No deliveries yet")
			.contains("Each delivery you finish shows up here with what you earned.")
			.contains("href=\"/partner\"")
			.doesNotContain("<table");
	}

	@Test
	void pendingAndSuspendedRidersSeeTheirStatusBanner() throws Exception {
		assertThat(page(rider(RiderStatus.PENDING), "/partner/earnings")).contains("Waiting for review.");
		assertThat(page(rider(RiderStatus.SUSPENDED), "/partner/earnings"))
			.contains("Your rider account is suspended.")
			.contains("Three late deliveries");
	}

	@Test
	void theWeekCanStartLastMonthAndTheMonthBeforeTheWeek() throws Exception {
		Kitchen k = kitchen("Calendar Kitchen");
		User rider = rider(RiderStatus.APPROVED);
		deliverAt(k, rider, PaymentMethod.ONLINE, LocalDateTime.of(2026, 10, 1, 13, 0)); // Thursday
		deliverAt(k, rider, PaymentMethod.ONLINE, LocalDateTime.of(2026, 10, 12, 13, 0)); // Monday
		clock.setTo(LocalDateTime.of(2026, 10, 14, 9, 0)); // Wednesday
		String html = page(rider, "/partner/earnings");
		assertThat(html).contains("₹54</dd> <dd class=\"report-stat-note\">1 delivery since Mon 12 Oct</dd>")
			.contains("<dt>This month · October</dt> <dd class=\"owner-stat-value\">₹108</dd> "
					+ "<dd class=\"report-stat-note\">2 deliveries</dd>")
			.contains("<dt>Today</dt> <dd class=\"owner-stat-value\">₹0</dd>");
	}

	@Test
	void historyPagesTwentyAtATimeWithCanonicalUrls() throws Exception {
		Kitchen k = kitchen("Paging Kitchen");
		User rider = rider(RiderStatus.APPROVED);
		RiderEarning first = deliverAt(k, rider, PaymentMethod.ONLINE, LocalDateTime.of(2026, 10, 2, 9, 0));
		for (int i = 0; i < 20; i++) {
			deliverAt(k, rider, PaymentMethod.ONLINE, LocalDateTime.of(2026, 10, 3, 9, 0).plusMinutes(i * 30L));
		}

		String one = page(rider, "/partner/earnings");
		assertThat(one).contains("Showing 1–20 of 21 deliveries, newest first")
			.contains("rel=\"next\" href=\"/partner/earnings?page=2\"")
			.doesNotContain("<th scope=\"row\">" + code(first) + "</th>");
		String two = page(rider, "/partner/earnings?page=2");
		assertThat(two).contains("Showing 21–21 of 21 deliveries, newest first")
			.contains("<th scope=\"row\">" + code(first) + "</th>")
			.contains("rel=\"prev\" href=\"/partner/earnings\"");

		for (String junk : List.of("?page=1", "?page=0", "?page=-2", "?page=two", "?page=")) {
			mvc.perform(get("/partner/earnings" + junk).with(as(rider))).andExpect(redirectedUrl("/partner/earnings"));
		}
		mvc.perform(get("/partner/earnings?page=9").with(as(rider)))
			.andExpect(redirectedUrl("/partner/earnings?page=2"));
		User fresh = rider(RiderStatus.APPROVED);
		mvc.perform(get("/partner/earnings?page=2").with(as(fresh))).andExpect(redirectedUrl("/partner/earnings"));
	}

	@Test
	void thePageRunsAFixedNumberOfQueries() throws Exception {
		Kitchen k = kitchen("Query Kitchen");
		User rider = rider(RiderStatus.APPROVED);
		deliverAt(k, rider, PaymentMethod.COD, LocalDateTime.of(2026, 10, 4, 12, 0));
		page(rider, "/partner/earnings"); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		page(rider, "/partner/earnings");
		long one = stats.getPrepareStatementCount();
		for (int i = 0; i < 6; i++) {
			deliverAt(k, rider, i % 2 == 0 ? PaymentMethod.COD : PaymentMethod.ONLINE,
					LocalDateTime.of(2026, 10, 4, 13, 0).plusMinutes(i));
		}
		stats.clear();
		assertThat(page(rider, "/partner/earnings")).contains("Showing 1–7 of 7 deliveries");
		long seven = stats.getPrepareStatementCount();
		// profile with account, totals, the page with orders and restaurants, cart count: none per delivery
		assertThat(seven).isEqualTo(one);
		assertThat(one).isLessThanOrEqualTo(5);
	}

	// ---------------------------------------------------------------- navigation and access

	@Test
	void theRiderPanelNavLinksDashboardAndEarnings() throws Exception {
		User rider = rider(RiderStatus.APPROVED);
		String earnings = page(rider, "/partner/earnings");
		assertThat(earnings).contains("aria-label=\"Rider panel\"")
			.contains("<a class=\"tab\" href=\"/partner/earnings\" aria-current=\"page\">");
		String dashboard = mvc.perform(get("/partner").with(as(rider)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
		assertThat(dashboard).contains("aria-label=\"Rider panel\"")
			.contains("<a class=\"tab\" href=\"/partner\" aria-current=\"page\">")
			.contains("<a class=\"tab\" href=\"/partner/earnings\">");
	}

	@Test
	void onlyRidersSeeEarnings() throws Exception {
		mvc.perform(get("/partner/earnings")).andExpect(redirectedUrl("/login"));
		Kitchen k = kitchen("Guarded Kitchen");
		for (User notARider : List.of(orders.customer(), k.restaurant().getOwner())) {
			mvc.perform(get("/partner/earnings").with(as(notARider))).andExpect(status().isForbidden());
		}
		// a rider account without a profile gets the same help as on the dashboard
		assertThat(page(orders.rider(), "/partner/earnings")).contains("No rider profile on this account")
			.doesNotContain("Cash in hand");
	}

}
