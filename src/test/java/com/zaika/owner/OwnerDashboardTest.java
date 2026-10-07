package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
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
 * T29: the owner dashboard's numbers (today's orders, revenue, average order, waiting to accept), the latest orders,
 * the 7-day revenue chart as server-side SVG, and the accepting-orders switch (plain form and htmx), each owner seeing
 * and changing only their own restaurant. The clock stands at Sunday 4 Oct 2026, 7:30 PM.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ownerdash;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OwnerDashboardTest {

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
	RestaurantRepository restaurants;

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
		String slug = "dash-" + UUID.randomUUID().toString().substring(0, 8);
		return kitchen(fixtures.restaurant(slug, "Dashboard Kitchen"));
	}

	// ---------------------------------------------------------------- numbers and chart

	@Test
	void todaysNumbersCountOnlyOrdersPlacedTodayThatAreStillOn() throws Exception {
		Kitchen k = kitchen();
		long c = orders.customer().getId();
		orders.place(c, k.thali().getId(), 2, PaymentMethod.COD, OrderStatus.PLACED); // ₹500, waiting
		orders.place(c, k.lassi().getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED); // ₹60
		orders.placeThenCancel(c, k.thali().getId(), 1, PaymentMethod.COD); // cancelled: not counted
		orders.place(c, k.thali().getId(), 3, PaymentMethod.COD, OrderStatus.REJECTED); // rejected: not counted
		orders.create(c, k.thali().getId(), 1, PaymentMethod.ONLINE); // never paid: not placed
		clock.setTo(MutableClock.DEFAULT_NOW.minusDays(1));
		orders.place(c, k.thali().getId(), 4, PaymentMethod.COD, OrderStatus.DELIVERED); // yesterday ₹1,000
		clock.setTo(MutableClock.DEFAULT_NOW.minusDays(6).withHour(0).withMinute(0));
		orders.place(c, k.lassi().getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED); // first day, midnight: ₹60
		clock.setTo(MutableClock.DEFAULT_NOW.minusDays(7).withHour(23).withMinute(59));
		orders.place(c, k.thali().getId(), 8, PaymentMethod.COD, OrderStatus.DELIVERED); // a week ago: outside
		clock.reset();
		orders.place(orders.customer().getId(), kitchen().thali().getId(), 9, PaymentMethod.COD, OrderStatus.PLACED); // other kitchen

		String html = ownerPage(k);
		assertThat(html).contains("Today <span class=\"owner-today-date\">· Sunday 4 Oct</span>")
			.contains("<dt>Orders</dt> <dd class=\"owner-stat-value\">2</dd>")
			.contains("<dt>Revenue</dt> <dd class=\"owner-stat-value\">₹560</dd>")
			.contains("<dt>Average order</dt> <dd class=\"owner-stat-value\">₹280</dd>")
			.contains("<div class=\"card owner-stat is-waiting\"> <dt>Waiting to accept</dt> <dd class=\"owner-stat-value\">1</dd>")
			.contains("before coupons, fees and GST. Cancelled and rejected orders don't count.")
			// chart: ₹1,620 over the week, best day ₹1,000 -> scale ₹1,000 / ₹500 / ₹0
			.contains("<strong>₹1,620</strong> in sales")
			.contains("<span>₹1,000</span><span>₹500</span><span>₹0</span>")
			.contains("viewBox=\"0 0 700 200\"")
			// 560 / 1000 x 200 high, standing on the baseline (y + height = 200), centred in the last of 7 slots
			.contains("class=\"revenue-chart-bar is-today\" x=\"622.0\" y=\"88.0\" width=\"56.0\" height=\"112.0\"></rect>")
			.contains("class=\"revenue-chart-bar\" x=\"522.0\" y=\"0.0\" width=\"56.0\" height=\"200.0\"></rect>")
			.contains("class=\"revenue-chart-bar\" x=\"22.0\" y=\"188.0\" width=\"56.0\" height=\"12.0\"></rect>")
			.doesNotContain("x=\"122.0\"") // no bar for a day without sales
			.contains("<li title=\"Sunday 4 Oct: ₹560\" class=\"is-today\"><span>Today</span><span>4 Oct</span></li>")
			.contains("<li title=\"Tuesday 29 Sep: ₹0\"><span>Tue</span><span>29 Sep</span></li>")
			// the same numbers for screen readers
			.contains("<caption>Sales by day, last 7 days</caption>")
			.contains("<th scope=\"row\">Today, Sunday 4 Oct</th><td>₹560</td>")
			.contains("<th scope=\"row\">Tuesday 29 Sep</th><td>₹0</td>")
			.doesNotContain("27 Sep", "No sales in the last 7 days yet");
	}

	@Test
	void aKitchenWithoutOrdersShowsZerosAndFriendlyEmptyStates() throws Exception {
		String html = ownerPage(kitchen());
		assertThat(html).contains("<dt>Orders</dt> <dd class=\"owner-stat-value\">0</dd>")
			.contains("<dt>Revenue</dt> <dd class=\"owner-stat-value\">₹0</dd>")
			.contains("<dd class=\"owner-stat-value\">–</dd> <dd class=\"visually-hidden\">No orders yet today</dd>")
			.contains("<div class=\"card owner-stat\"> <dt>Waiting to accept</dt>")
			.contains("No sales in the last 7 days yet.", "No orders yet. When customers order from you")
			.doesNotContain("<rect", "owner-orders");
	}

	@Test
	void latestOrdersAreTheFiveNewestPlacedOnesWithTheirDishes() throws Exception {
		Kitchen k = kitchen();
		long c = orders.customer().getId();
		clock.setTo(MutableClock.DEFAULT_NOW.minusMinutes(30));
		Order oldest = orders.place(c, k.lassi().getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		String[] codes = new String[5];
		for (int i = 0; i < 5; i++) {
			clock.advance(Duration.ofMinutes(5));
			codes[i] = orders.place(c, i % 2 == 0 ? k.thali().getId() : k.lassi().getId(), i + 1, PaymentMethod.COD,
					i == 4 ? OrderStatus.PLACED : OrderStatus.PREPARING).getCode();
		}
		Order unpaid = orders.create(c, k.thali().getId(), 1, PaymentMethod.ONLINE); // newest, but not placed
		clock.reset();

		String html = ownerPage(k);
		assertThat(html).doesNotContain(oldest.getCode(), unpaid.getCode());
		int previous = Integer.MAX_VALUE;
		for (String code : codes) { // newest first: each code appears before the older one
			int at = html.indexOf(">" + code + "</a>");
			assertThat(at).isPositive().isLessThan(previous);
			previous = at;
		}
		assertThat(html)
			.contains("<a href=\"/owner/orders/" + codes[4] + "\">" + codes[4] + "</a> <span class=\"badge badge-primary\">Placed</span>")
			.contains("<a href=\"/owner/orders/" + codes[3] + "\">" + codes[3] + "</a> <span class=\"badge badge-primary\">Preparing</span>")
			.contains("5 × Mutton Thali")
			.contains("<span class=\"price\">₹1,250</span>") // food value 5 x 250
			.contains("title=\"4 Oct 2026, 7:25 PM\">5 min ago</time>");
	}

	@Test
	void theDashboardRunsAFixedNumberOfQueries() throws Exception {
		Kitchen few = kitchen();
		Kitchen many = kitchen();
		long c = orders.customer().getId();
		orders.place(c, few.thali().getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		for (int i = 0; i < 8; i++) {
			orders.place(c, many.thali().getId(), 1, PaymentMethod.COD, OrderStatus.ACCEPTED);
		}
		RequestPostProcessor asFew = as(few.restaurant().getOwner());
		RequestPostProcessor asMany = as(many.restaurant().getOwner());
		html(get("/owner").with(asFew)); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		html(get("/owner").with(asFew));
		long one = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/owner").with(asMany));
		long eight = stats.getPrepareStatementCount();
		// restaurant + cuisines, sales, latest orders, their dishes, waiting count: nothing per order or dish
		assertThat(eight).isEqualTo(one);
		assertThat(one).isLessThanOrEqualTo(5);
	}

	@Test
	void eachOwnerSeesOnlyTheirOwnNumbersAndOrders() throws Exception {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		long c = orders.customer().getId();
		String theirCode = orders.place(c, theirs.thali().getId(), 3, PaymentMethod.COD, OrderStatus.PLACED).getCode();
		assertThat(ownerPage(mine)).doesNotContain(theirCode, "₹750")
			.contains("<dt>Orders</dt> <dd class=\"owner-stat-value\">0</dd>");
	}

	// ---------------------------------------------------------------- accepting orders switch

	@Test
	void theSwitchShowsTheCurrentStateAsAnAccessibleSwitch() throws Exception {
		String html = ownerPage(kitchen());
		assertThat(html).contains("id=\"owner-accepting\" class=\"card owner-accepting is-on\"")
			.contains("<form class=\"owner-accepting-form\" method=\"post\" action=\"/owner/accepting\" hx-post=\"/owner/accepting\"")
			.contains("name=\"_csrf\"")
			.contains("<input type=\"hidden\" name=\"accepting\" value=\"false\">")
			.contains("<button id=\"owner-accepting-switch\" class=\"switch\" type=\"submit\" role=\"switch\" aria-checked=\"true\" aria-labelledby=\"owner-accepting-title\" aria-describedby=\"owner-accepting-help\">")
			.contains("<strong>On.</strong> New orders come in as customers place them.");
	}

	@Test
	void pausingWithoutJavaScriptRedirectsWithAToastAndStopsNewOrders() throws Exception {
		Kitchen k = kitchen();
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()).with(as(k.restaurant().getOwner())))
			.andExpect(redirectedUrl("/owner"))
			.andExpect(flash().attribute("flashInfo", "New orders paused. Turn it back on any time."));
		assertThat(restaurants.findById(k.restaurant().getId()).orElseThrow().isAcceptingOrders()).isFalse();
		assertThat(ownerPage(k)).contains("class=\"card owner-accepting is-paused\"")
			.contains("<input type=\"hidden\" name=\"accepting\" value=\"true\">")
			.contains("aria-checked=\"false\"")
			.contains("<strong>Paused.</strong> Customers can see your menu but can't order");
		// customers see it straight away
		assertThat(html(get("/r/" + k.restaurant().getSlug()))).contains("Not taking orders right now.");

		mvc.perform(post("/owner/accepting").param("accepting", "true").with(csrf()).with(as(k.restaurant().getOwner())))
			.andExpect(redirectedUrl("/owner"))
			.andExpect(flash().attribute("flashSuccess", "You're taking orders again."));
		assertThat(restaurants.findById(k.restaurant().getId()).orElseThrow().isAcceptingOrders()).isTrue();
	}

	@Test
	void withHtmxOnlyTheSwitchCardIsSwappedWithAToast() throws Exception {
		Kitchen k = kitchen();
		String html = mvc.perform(post("/owner/accepting").param("accepting", "false").header("HX-Request", "true")
				.with(csrf()).with(as(k.restaurant().getOwner())))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).startsWith(" <section id=\"owner-accepting\" class=\"card owner-accepting is-paused\"")
			.contains("aria-checked=\"false\"")
			.contains("<div hx-swap-oob=\"beforeend:#toasts\"><div class=\"toast\"")
			.contains("New orders paused. Turn it back on any time.")
			.doesNotContain("<html", "owner-today", "Latest orders");
		assertThat(restaurants.findById(k.restaurant().getId()).orElseThrow().isAcceptingOrders()).isFalse();
	}

	@Test
	void outsideItsHoursTheSwitchSaysWhenOrdersStart() throws Exception {
		Kitchen k = kitchen(fixtures.restaurant("dash-breakfast", "Breakfast Kitchen").hours("06:00", "10:00"));
		assertThat(ownerPage(k)).contains("<strong>On, but you're outside your hours.</strong> Customers can order again when you open at 6:00 AM.");
	}

	@Test
	void theSwitchOnlyShowsOnceTheRestaurantIsLive() throws Exception {
		Kitchen k = kitchen(fixtures.restaurant("dash-pending", "Pending Kitchen").status(RestaurantStatus.PENDING));
		assertThat(ownerPage(k)).contains("Waiting for review.").doesNotContain("owner-accepting");
	}

	@Test
	void anOwnerChangesOnlyTheirOwnRestaurant() throws Exception {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()).with(as(mine.restaurant().getOwner())))
			.andExpect(redirectedUrl("/owner"));
		assertThat(restaurants.findById(mine.restaurant().getId()).orElseThrow().isAcceptingOrders()).isFalse();
		assertThat(restaurants.findById(theirs.restaurant().getId()).orElseThrow().isAcceptingOrders()).isTrue();
	}

	@Test
	void anOwnerWithoutARestaurantGets404() throws Exception {
		User owner = users.save(new User("Lone Owner", "lone@ownerdash.zaika.test", "9876543210", "{noop}x",
				Role.RESTAURANT_OWNER));
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()).with(as(owner)))
			.andExpect(status().isNotFound());
	}

	@Test
	void theSwitchNeedsAnOwnerACsrfTokenAndAValue() throws Exception {
		Kitchen k = kitchen();
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		User customer = users.save(new User("Customer", "cust@ownerdash.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()).with(as(customer)))
			.andExpect(status().isForbidden());
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(as(k.restaurant().getOwner())))
			.andExpect(status().isForbidden());
		mvc.perform(post("/owner/accepting").with(csrf()).with(as(k.restaurant().getOwner())))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/owner/accepting").param("accepting", "maybe").with(csrf()).with(as(k.restaurant().getOwner())))
			.andExpect(status().isBadRequest());
		assertThat(restaurants.findById(k.restaurant().getId()).orElseThrow().isAcceptingOrders()).isTrue();
	}

	// ---------------------------------------------------------------- helpers

	private RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	private String ownerPage(Kitchen k) throws Exception {
		return html(get("/owner").with(as(k.restaurant().getOwner())));
	}

	private String html(RequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

}
