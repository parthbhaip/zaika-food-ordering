package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T33: the owner's order history and reports: date range and status filters with canonical URLs, the paginated
 * orders table, the revenue summary, the top five dishes and the CSV export, each owner seeing only their own
 * restaurant. The clock stands at Sunday 4 Oct 2026, 7:30 PM, so the default range is 5 Sep - 4 Oct 2026.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ownerreports;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OwnerReportsTest {

	@Autowired
	MockMvc mvc;

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
	ZaikaUserDetailsService userDetails;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** A restaurant (open 11 AM to 11 PM) with a ₹250 thali and a ₹60 lassi. */
	record Kitchen(Restaurant restaurant, MenuItem thali, MenuItem lassi) {
	}

	Kitchen kitchen(CatalogFixtures.Builder builder) {
		Restaurant r = builder.save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		return new Kitchen(r, items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false)),
				items.save(new MenuItem(menu, "Sweet Lassi", new BigDecimal("60"), true)));
	}

	Kitchen kitchen() {
		return kitchen(fixtures.restaurant("rep-" + UUID.randomUUID().toString().substring(0, 8), "Report Kitchen"));
	}

	/** Orders across the default range and around its edges, plus some that must never show. */
	record Scene(Kitchen k, Order waiting, Order delivered, Order cancelled, Order rejected, Order unpaid, Order firstDay,
			Order tooOld, Order otherKitchen) {
	}

	Scene scene() {
		Kitchen k = kitchen();
		long c = orders.customer().getId();
		Order waiting = orders.place(c, k.thali().getId(), 2, PaymentMethod.COD, OrderStatus.PLACED); // ₹500
		Order delivered = orders.place(c, k.lassi().getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED); // ₹60
		Order cancelled = orders.placeThenCancel(c, k.thali().getId(), 1, PaymentMethod.COD); // missed ₹250
		Order rejected = orders.place(c, k.thali().getId(), 3, PaymentMethod.COD, OrderStatus.REJECTED); // missed ₹750
		Order unpaid = orders.create(c, k.thali().getId(), 1, PaymentMethod.ONLINE); // never reached the kitchen
		clock.setTo(MutableClock.DEFAULT_NOW.minusDays(29).withHour(0).withMinute(0));
		Order firstDay = orders.place(c, k.thali().getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED); // ₹250
		clock.setTo(MutableClock.DEFAULT_NOW.minusDays(30).withHour(23).withMinute(59));
		Order tooOld = orders.place(c, k.thali().getId(), 8, PaymentMethod.COD, OrderStatus.DELIVERED); // outside
		clock.reset();
		Order other = orders.place(orders.customer().getId(), kitchen().thali().getId(), 9, PaymentMethod.COD,
				OrderStatus.DELIVERED);
		return new Scene(k, waiting, delivered, cancelled, rejected, unpaid, firstDay, tooOld, other);
	}

	// ---------------------------------------------------------------- page

	@Test
	void theDefaultReportCoversTheLast30Days() throws Exception {
		Scene s = scene();
		String html = page(s.k(), "/owner/reports");
		assertThat(html).contains("<title>Reports | Zaika</title>", "<h1 class=\"owner-title\">Orders and reports</h1>")
			.contains("aria-current=\"page\"><svg class=\"icon\" aria-hidden=\"true\" focusable=\"false\"><use href=\"/img/icons.svg#chart\"></use></svg>Reports</a>")
			.contains("Sales <span class=\"owner-today-date\">· 5 Sep 2026 – 4 Oct 2026</span>", ">30 days<")
			// sales: ₹500 + ₹60 + ₹250 on the first day; the cancelled and rejected orders are missed
			.contains("<dt>Revenue</dt> <dd class=\"owner-stat-value\">₹810</dd>")
			.contains("<dt>Orders</dt> <dd class=\"owner-stat-value\">3</dd>")
			.contains("<dt>Average order</dt> <dd class=\"owner-stat-value\">₹270</dd>")
			.contains("<dt>Cancelled or rejected</dt> <dd class=\"owner-stat-value\">2</dd> <dd class=\"report-stat-note\">₹1,000 of food</dd>")
			// top dishes: 3 thalis (2 + 1), 1 lassi; the rejected and cancelled thalis don't count
			.containsPattern("<span class=\"report-dish-rank\" aria-hidden=\"true\">1</span> <span class=\"veg-mark non-veg\">.*?</span> "
					+ "<span class=\"report-dish-name\">Mutton Thali</span> <span class=\"report-dish-qty\">3 sold</span> "
					+ "<span class=\"price report-dish-sales\">₹750</span>")
			.contains("<span class=\"report-dish-name\">Sweet Lassi</span> <span class=\"report-dish-qty\">1 sold</span>")
			.contains("Showing 1–5 of 5 orders, newest first")
			.contains(s.waiting().getCode(), s.delivered().getCode(), s.cancelled().getCode(), s.rejected().getCode(),
					s.firstDay().getCode())
			.doesNotContain(s.unpaid().getCode(), s.tooOld().getCode(), s.otherKitchen().getCode())
			.contains("<a href=\"/owner/orders/" + s.waiting().getCode() + "\">" + s.waiting().getCode() + "</a>")
			.contains("<td class=\"report-items\">2 × Mutton Thali</td>", "<td class=\"num\">₹500.00</td>")
			.contains(">Rejected</span>", ">Cancelled</span>", ">Delivered</span>", ">Placed</span>")
			.contains("<a class=\"chip\" href=\"/owner/reports\" aria-current=\"true\">Last 30 days</a>")
			.contains("<a class=\"chip\" href=\"/owner/reports?from=2026-10-04&amp;to=2026-10-04\">Today</a>")
			.contains("<a class=\"chip\" href=\"/owner/reports?from=2026-09-01&amp;to=2026-09-30\">Last month</a>")
			.contains("value=\"2026-09-05\"", "value=\"2026-10-04\"", "<option value=\"\">All statuses</option>")
			.contains("href=\"/owner/reports/orders.csv\" download")
			.doesNotContain("pagination");
		// newest first: today's orders, then the first day's
		assertThat(html.indexOf(s.rejected().getCode())).isLessThan(html.indexOf(s.waiting().getCode()));
		assertThat(html.indexOf(s.waiting().getCode())).isLessThan(html.indexOf(s.firstDay().getCode()));
	}

	@Test
	void theDatesAndTheStatusNarrowTheReport() throws Exception {
		Scene s = scene();
		String html = page(s.k(), "/owner/reports?from=2026-10-04&to=2026-10-04&status=delivered");
		assertThat(html).contains("· 4 Oct 2026 – 4 Oct 2026", ">1 day<")
			// the summary covers the whole day, every status
			.contains("<dt>Revenue</dt> <dd class=\"owner-stat-value\">₹560</dd>")
			.contains("<dt>Orders</dt> <dd class=\"owner-stat-value\">2</dd>")
			// the table only the delivered order
			.contains("<span class=\"badge badge-primary\">Delivered</span>", "One order")
			.contains(s.delivered().getCode())
			.doesNotContain(s.waiting().getCode(), s.rejected().getCode(), s.firstDay().getCode())
			.contains("<option value=\"delivered\" selected=\"selected\">Delivered</option>")
			.contains("<a class=\"chip\" href=\"/owner/reports?from=2026-10-04&amp;to=2026-10-04&amp;status=delivered\" aria-current=\"true\">Today</a>")
			.contains("<a class=\"chip\" href=\"/owner/reports?status=delivered\">Last 30 days</a>")
			.contains("href=\"/owner/reports/orders.csv?from=2026-10-04&amp;to=2026-10-04&amp;status=delivered\"");

		assertThat(page(s.k(), "/owner/reports?status=rejected")).contains(s.rejected().getCode())
			.doesNotContain(s.delivered().getCode(), s.cancelled().getCode());
		assertThat(page(s.k(), "/owner/reports?from=2026-09-05&to=2026-09-05")).contains(s.firstDay().getCode())
			.doesNotContain(s.waiting().getCode())
			.contains("<dt>Revenue</dt> <dd class=\"owner-stat-value\">₹250</dd>");
	}

	@Test
	void aPeriodWithoutOrdersShowsEmptyStates() throws Exception {
		Kitchen k = kitchen();
		String html = page(k, "/owner/reports");
		assertThat(html).contains("No orders in this period", "Try a longer range.", "No dishes sold in this period yet.")
			.contains("<dt>Revenue</dt> <dd class=\"owner-stat-value\">₹0</dd>")
			.contains("<dd class=\"owner-stat-value\">–</dd> <dd class=\"visually-hidden\">No orders in this period</dd>")
			.contains("None missed")
			.doesNotContain("Download CSV", "<table");
		assertThat(page(k, "/owner/reports?status=out-for-delivery"))
			.contains("No out for delivery orders between these dates. Try another status or a longer range.");
	}

	@Test
	void nonCanonicalUrlsRedirect() throws Exception {
		Kitchen k = kitchen();
		RequestPostProcessor owner = as(k.restaurant().getOwner());
		String[][] cases = {
				{ "/owner/reports?from=2026-09-05&to=2026-10-04", "/owner/reports" },
				{ "/owner/reports?from=&to=&status=&page=1", "/owner/reports" },
				{ "/owner/reports?from=yesterday&page=two", "/owner/reports" },
				{ "/owner/reports?status=pending-payment", "/owner/reports" },
				{ "/owner/reports?status=whatever", "/owner/reports" },
				{ "/owner/reports?status=READY_FOR_PICKUP", "/owner/reports?status=ready-for-pickup" },
				{ "/owner/reports?status=DELIVERED", "/owner/reports?status=delivered" },
				{ "/owner/reports?to=2026-09-30&from=2026-09-01", "/owner/reports?from=2026-09-01&to=2026-09-30" },
				{ "/owner/reports?from=2026-10-04&to=2026-09-01", "/owner/reports?from=2026-09-01&to=2026-10-04" },
				{ "/owner/reports?from=2026-09-20", "/owner/reports?from=2026-09-20&to=2026-10-04" },
				{ "/owner/reports?to=2026-09-30", "/owner/reports?from=2026-09-01&to=2026-09-30" },
				{ "/owner/reports?page=4", "/owner/reports" },
				{ "/owner/reports?page=0", "/owner/reports" },
				// dates outside 2000-01-01 .. a year from today count as missing
				{ "/owner/reports?to=%2B999999999-12-31", "/owner/reports" },
				{ "/owner/reports?from=2026-09-01&to=9999-12-31", "/owner/reports?from=2026-09-01&to=2026-10-04" },
				{ "/owner/reports?from=1999-12-31&to=2026-09-30", "/owner/reports?from=2026-09-01&to=2026-09-30" } };
		for (String[] c : cases) {
			mvc.perform(get(URI.create(c[0])).with(owner)).andExpect(redirectedUrl(c[1]));
		}
		mvc.perform(get(URI.create("/owner/reports/orders.csv?from=-999999999-01-01&to=%2B999999999-12-31")).with(owner))
			.andExpect(status().isOk());
		mvc.perform(get("/owner/reports?from=2024-01-01&to=2026-10-04").with(owner))
			.andExpect(redirectedUrl("/owner/reports?from=2025-10-04&to=2026-10-04"))
			.andExpect(flash().attribute("flashInfo", "Reports cover up to a year at a time, so this one starts on 4 Oct 2025."));
		page(k, "/owner/reports?from=2025-10-04&to=2026-10-04");
		page(k, "/owner/reports?status=ready-for-pickup");
	}

	@Test
	void theTableShows20OrdersAPage() throws Exception {
		Kitchen k = kitchen();
		long c = orders.customer().getId();
		Order oldest = null;
		for (int n = 0; n < 21; n++) {
			Order o = orders.place(c, k.lassi().getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
			oldest = oldest == null ? o : oldest;
		}
		String first = page(k, "/owner/reports");
		assertThat(first).contains("Showing 1–20 of 21 orders, newest first", "class=\"pagination\"",
				"href=\"/owner/reports?page=2\"")
			.doesNotContain(oldest.getCode());
		assertThat(first.split("<tr>").length - 2).isEqualTo(20);
		String second = page(k, "/owner/reports?page=2");
		assertThat(second).contains("Showing 21–21 of 21 orders", oldest.getCode(), "href=\"/owner/reports\"");
		mvc.perform(get("/owner/reports?page=3").with(as(k.restaurant().getOwner())))
			.andExpect(redirectedUrl("/owner/reports?page=2"));
		assertThat(page(k, "/owner/reports?status=placed&page=2")).contains(oldest.getCode())
			.contains("href=\"/owner/reports?status=placed\"");
	}

	@Test
	void aReportTakesAFixedNumberOfStatements() throws Exception {
		Kitchen few = kitchen();
		orders.place(orders.customer().getId(), few.thali().getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		Kitchen many = kitchen();
		for (int n = 0; n < 8; n++) {
			orders.place(orders.customer().getId(), n % 2 == 0 ? many.thali().getId() : many.lassi().getId(), n + 1,
					PaymentMethod.COD, n % 3 == 0 ? OrderStatus.DELIVERED : OrderStatus.PREPARING);
		}
		RequestPostProcessor asFew = as(few.restaurant().getOwner());
		RequestPostProcessor asMany = as(many.restaurant().getOwner());
		html(get("/owner/reports").with(asFew)); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		html(get("/owner/reports").with(asFew));
		long one = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/owner/reports").with(asMany));
		long eight = stats.getPrepareStatementCount();
		// restaurant, totals by status, top dishes, the page, its dishes: nothing per order, customer or dish
		assertThat(eight).isEqualTo(one);
		assertThat(one).isLessThanOrEqualTo(5);
	}

	@Test
	void namesAreEscapedOnThePage() throws Exception {
		Kitchen k = kitchen();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(k.restaurant().getId()).get(0);
		MenuItem odd = items.save(new MenuItem(menu, "Misal <b>Special</b>", new BigDecimal("120"), true));
		orders.place(orders.customer().getId(), odd.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		assertThat(page(k, "/owner/reports")).contains("Misal &lt;b&gt;Special&lt;/b&gt;").doesNotContain("<b>Special");
	}

	@Test
	void pendingRestaurantsSeeTheirStatusBanner() throws Exception {
		Kitchen k = kitchen(fixtures.restaurant("rep-pending-" + UUID.randomUUID().toString().substring(0, 6),
				"Pending Kitchen").status(RestaurantStatus.PENDING));
		assertThat(page(k, "/owner/reports")).contains("Waiting for review.");
	}

	@Test
	void theOwnerNavAndMenuLinkToReports() throws Exception {
		Kitchen k = kitchen();
		for (String url : new String[] { "/owner", "/owner/orders", "/owner/menu", "/owner/restaurant" }) {
			assertThat(page(k, url)).as(url).contains("href=\"/owner/reports\"");
		}
	}

	// ---------------------------------------------------------------- CSV

	@Test
	void theCsvHasEveryOrderOfTheReport() throws Exception {
		Scene s = scene();
		MockHttpServletResponse response = mvc.perform(get("/owner/reports/orders.csv").with(as(s.k().restaurant().getOwner())))
			.andExpect(status().isOk())
			.andReturn().getResponse();
		assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
		assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"zaika-orders-"
				+ s.k().restaurant().getSlug() + "-2026-09-05-to-2026-10-04.csv\"");
		String csv = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿Order,Placed,Status,Customer,Dishes,Items,Payment,Payment status,Food value,"
				+ "Discount,Delivery fee,Platform fee,GST,Order total,Coupon,Reason\r\n");
		String[] lines = csv.split("\r\n");
		assertThat(lines).hasSize(6); // header + 5 orders
		Order waiting = s.waiting();
		var bill = waiting.getBill();
		assertThat(csv).contains(waiting.getCode() + ",2026-10-04 19:30,Placed,Riya Shinde,2 x Mutton Thali,2,"
				+ "Cash on delivery,Pending,500.00,0.00," + bill.deliveryFee() + "," + bill.platformFee() + ","
				+ bill.gst() + "," + bill.grandTotal() + ",,\r\n")
			.contains(s.rejected().getCode() + ",2026-10-04 19:30,Rejected,")
			.contains(",Kitchen closing early\r\n", ",Ordered by mistake\r\n")
			.contains(s.firstDay().getCode() + ",2026-09-05 00:00,Delivered,")
			.doesNotContain(s.unpaid().getCode(), s.tooOld().getCode(), s.otherKitchen().getCode());
		// newest first, like the table
		assertThat(lines[lines.length - 1]).startsWith(s.firstDay().getCode());

		MockHttpServletResponse delivered = mvc.perform(get("/owner/reports/orders.csv?from=2026-10-04&to=2026-10-04&status=delivered")
			.with(as(s.k().restaurant().getOwner()))).andReturn().getResponse();
		assertThat(delivered.getHeader("Content-Disposition")).endsWith("-2026-10-04-to-2026-10-04-delivered.csv\"");
		assertThat(delivered.getContentAsString(StandardCharsets.UTF_8).split("\r\n")).hasSize(2);
		assertThat(delivered.getContentAsString(StandardCharsets.UTF_8)).contains(s.delivered().getCode());
	}

	@Test
	void csvCellsAreQuotedAndCannotRunFormulas() throws Exception {
		Kitchen k = kitchen();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(k.restaurant().getId()).get(0);
		MenuItem odd = items.save(new MenuItem(menu, "Misal \"Special\", extra tarri", new BigDecimal("120"), true));
		MenuItem formula = items.save(new MenuItem(menu, "=HYPERLINK(\"http://x.test\")", new BigDecimal("10"), true));
		User customer = users.save(new User("@SUM(1+1)", "formula-" + UUID.randomUUID() + "@reports.zaika.test",
				"9876543210", "hash", Role.CUSTOMER));
		orders.place(customer.getId(), odd.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		orders.place(customer.getId(), formula.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		String csv = mvc.perform(get("/owner/reports/orders.csv").with(as(k.restaurant().getOwner())))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(csv).contains(",'@SUM(1+1),\"1 x Misal \"\"Special\"\", extra tarri\",1,")
			.contains(",'@SUM(1+1),\"1 x =HYPERLINK(\"\"http://x.test\"\")\",1,");

		assertThat(OwnerReportsService.cell(null)).isEmpty();
		assertThat(OwnerReportsService.cell("plain")).isEqualTo("plain");
		assertThat(OwnerReportsService.cell("=1+2")).isEqualTo("'=1+2");
		assertThat(OwnerReportsService.cell("+91")).isEqualTo("'+91");
		assertThat(OwnerReportsService.cell("-5")).isEqualTo("'-5");
		assertThat(OwnerReportsService.cell("\tx")).isEqualTo("'\tx");
		assertThat(OwnerReportsService.cell("\rx")).isEqualTo("\"'\rx\"");
		// the first non-whitespace character counts too
		assertThat(OwnerReportsService.cell(" =HYPERLINK(1)")).isEqualTo("' =HYPERLINK(1)");
		assertThat(OwnerReportsService.cell(" \t @SUM(1)")).isEqualTo("' \t @SUM(1)");
		assertThat(OwnerReportsService.cell("  plain")).isEqualTo("  plain");
		assertThat(OwnerReportsService.cell("two\nlines")).isEqualTo("\"two\nlines\"");
		assertThat(OwnerReportsService.cell("=a,b")).isEqualTo("\"'=a,b\"");
	}

	// ---------------------------------------------------------------- security

	@Test
	void eachOwnerSeesOnlyTheirOwnOrders() throws Exception {
		Scene s = scene();
		Kitchen mine = kitchen();
		assertThat(page(mine, "/owner/reports")).contains("No orders in this period")
			.doesNotContain(s.waiting().getCode(), s.otherKitchen().getCode());
		String csv = mvc.perform(get("/owner/reports/orders.csv").with(as(mine.restaurant().getOwner())))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(csv.split("\r\n")).hasSize(1);
	}

	@Test
	void reportsAreForOwnersOnly() throws Exception {
		mvc.perform(get("/owner/reports")).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/owner/reports/orders.csv")).andExpect(redirectedUrl("/login"));
		User customer = users.save(new User("Customer", "cust-" + UUID.randomUUID() + "@reports.zaika.test",
				"9876543210", "{noop}x", Role.CUSTOMER));
		mvc.perform(get("/owner/reports").with(as(customer))).andExpect(status().isForbidden());
		mvc.perform(get("/owner/reports/orders.csv").with(as(customer))).andExpect(status().isForbidden());
		User rider = users.save(new User("Rider", "rider-" + UUID.randomUUID() + "@reports.zaika.test",
				"9876543210", "{noop}x", Role.DELIVERY_PARTNER));
		mvc.perform(get("/owner/reports/orders.csv").with(as(rider))).andExpect(status().isForbidden());
	}

	@Test
	void anOwnerWithoutARestaurantGetsAnEmptyStateAndNoCsv() throws Exception {
		User owner = users.save(new User("Lone Owner", "lone-" + UUID.randomUUID() + "@reports.zaika.test",
				"9876543210", "{noop}x", Role.RESTAURANT_OWNER));
		assertThat(html(get("/owner/reports").with(as(owner)))).contains("No restaurant on this account");
		mvc.perform(get("/owner/reports/orders.csv").with(as(owner))).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- helpers

	private RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	private String page(Kitchen k, String url) throws Exception {
		return html(get(url).with(as(k.restaurant().getOwner())));
	}

	private String html(RequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

}
