package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponType;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T32: the owner's live orders board (New / Preparing / Ready columns, new orders highlighted, htmx polling that
 * answers 204 until something changed), accepting with a preparation time (updates the ETA), rejecting with a reason
 * (coupon given back, paid orders refunded), Mark preparing and Mark ready, all through OrderWorkflow, and the
 * print-friendly kitchen ticket. Each owner sees and moves only their own restaurant's orders. The clock stands at
 * Sunday 4 Oct 2026, 7:30 PM.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ownerorders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OwnerOrdersTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	OrderWorkflow workflow;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	CouponRepository coupons;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** A restaurant (20 min prep, open 11 AM to 11 PM) with a ₹250 thali and a ₹60 lassi. */
	record Kitchen(Restaurant restaurant, MenuItem thali, MenuItem lassi) {

		User owner() {
			return restaurant.getOwner();
		}

	}

	Kitchen kitchen() {
		String slug = "board-" + UUID.randomUUID().toString().substring(0, 8);
		Restaurant r = fixtures.restaurant(slug, "Board Kitchen").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		return new Kitchen(r, items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false)),
				items.save(new MenuItem(menu, "Sweet Lassi", new BigDecimal("60"), true)));
	}

	String place(Kitchen k, OrderStatus status) {
		return orders.place(orders.customer().getId(), k.thali().getId(), 2, PaymentMethod.COD, status).getCode();
	}

	// ---------------------------------------------------------------- the board

	@Test
	void theBoardSortsLiveOrdersIntoNewPreparingAndReadyWithNewOnesHighlighted() throws Exception {
		Kitchen k = kitchen();
		String fresh = place(k, OrderStatus.PLACED);
		String accepted = place(k, OrderStatus.ACCEPTED);
		String cooking = place(k, OrderStatus.PREPARING);
		String ready = place(k, OrderStatus.READY_FOR_PICKUP);
		String delivered = place(k, OrderStatus.DELIVERED);
		String rejected = place(k, OrderStatus.REJECTED);
		String unpaid = orders.create(orders.customer().getId(), k.thali().getId(), 1, PaymentMethod.ONLINE).getCode();
		String theirs = place(kitchen(), OrderStatus.PLACED);

		String html = board(k);
		assertThat(html).contains("<title>Live orders | Zaika</title>")
			.contains("<a class=\"tab\" href=\"/owner/orders\" aria-current=\"page\">")
			.contains("<h1 class=\"owner-title\">Live orders</h1>")
			.doesNotContain(delivered, rejected, unpaid, theirs);
		String newColumn = between(html, "id=\"board-new-title\"", "id=\"board-cooking-title\"");
		String cookingColumn = between(html, "id=\"board-cooking-title\"", "id=\"board-ready-title\"");
		String readyColumn = html.substring(html.indexOf("id=\"board-ready-title\""));
		assertThat(newColumn).contains("<span class=\"board-count is-waiting\">1</span>")
			.contains("<article class=\"card board-order is-new\" id=\"order-" + fresh + "\"")
			.contains("<span class=\"badge badge-primary badge-dot\">New</span>")
			.contains("Placed 7:30 PM").contains("For Riya")
			.contains("<span class=\"board-order-qty\">2 ×</span><span>Mutton Thali</span>")
			.contains("<strong>₹500</strong> <span>2 items</span> <span class=\"board-order-pay\">Cash on delivery</span>")
			.contains("<option value=\"20\" selected=\"selected\">Ready in 20 min</option>")
			.contains("<option value=\"45\">Ready in 45 min</option>")
			.contains("action=\"/owner/orders/" + fresh + "/accept\" hx-post=\"/owner/orders/" + fresh + "/accept\" hx-target=\"#owner-board\"")
			.contains("action=\"/owner/orders/" + fresh + "/reject\"")
			.contains("id=\"new-actions-" + fresh + "\" hx-preserve=\"true\"")
			.doesNotContain(accepted, cooking, ready);
		assertThat(cookingColumn).contains("<span class=\"board-count\">2</span>")
			.contains("<article class=\"card board-order\" id=\"order-" + accepted + "\"")
			.contains("action=\"/owner/orders/" + accepted + "/preparing\"")
			.contains("action=\"/owner/orders/" + cooking + "/ready\"")
			.contains("Ready by <strong>7:50 PM</strong>")
			.contains("href=\"/owner/orders/" + cooking + "\"")
			.doesNotContain(fresh, ready, "is-new");
		assertThat(readyColumn).contains("<span class=\"board-count\">1</span>").contains(ready)
			.contains("Waiting for a delivery partner")
			.doesNotContain("/accept", "/preparing", "/ready\"");
		// oldest first: the accepted order was placed before the one being cooked
		assertThat(cookingColumn.indexOf(accepted)).isLessThan(cookingColumn.indexOf(cooking));
	}

	@Test
	void theBoardPollsEveryTenSecondsAndAnnouncesChanges() throws Exception {
		Kitchen k = kitchen();
		String html = board(k);
		Matcher poll = Pattern.compile("hx-get=\"/owner/orders\\?v=([a-z0-9-]+)\" hx-trigger=\"every 10s\" hx-swap=\"outerHTML\"")
			.matcher(html);
		assertThat(poll.find()).isTrue();
		String v = poll.group(1);
		assertThat(html).contains("<p id=\"board-announce\" class=\"visually-hidden\" role=\"status\"></p>")
			.contains("No new orders. They show up here as customers place them.")
			.contains("Nothing on the stove.").contains("Nothing waiting for a rider.")
			.contains("<noscript><a href=\"/owner/orders\">Refresh now</a></noscript>");

		mvc.perform(get("/owner/orders").param("v", v).header("HX-Request", "true").with(as(k.owner())))
			.andExpect(status().isNoContent())
			.andExpect(header().string("Vary", "HX-Request"))
			.andExpect(content().string(""));

		String code = place(k, OrderStatus.PLACED);
		String fragment = mvc.perform(get("/owner/orders").param("v", v).header("HX-Request", "true").with(as(k.owner())))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(fragment).startsWith(" <section id=\"owner-board\"").contains(code)
			.contains("<p id=\"board-announce\" hx-swap-oob=\"innerHTML\">Orders updated. 1 new order waiting to accept.</p>")
			.doesNotContain("<html", "owner-nav")
			.doesNotContain("hx-get=\"/owner/orders?v=" + v + "\"");

		// any change to an order on the board (here: accepted elsewhere) is a new version too
		String now = currentVersion(k);
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findForUpdateByCode(code).orElseThrow(),
				OrderStatus.ACCEPTED, OrderActor.restaurant(k.owner().getId())));
		mvc.perform(get("/owner/orders").param("v", now).header("HX-Request", "true").with(as(k.owner())))
			.andExpect(status().isOk());
		// a history restore gets the whole page
		assertThat(mvc.perform(get("/owner/orders").param("v", currentVersion(k)).header("HX-Request", "true")
				.header("HX-History-Restore-Request", "true").with(as(k.owner())))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("<html");
	}

	@Test
	void aPausedKitchenIsRemindedAndTheBoardRunsAFixedNumberOfQueries() throws Exception {
		Kitchen few = kitchen();
		Kitchen many = kitchen();
		place(few, OrderStatus.PLACED);
		for (int i = 0; i < 8; i++) {
			place(many, i % 2 == 0 ? OrderStatus.PLACED : OrderStatus.READY_FOR_PICKUP);
		}
		RequestPostProcessor asFew = as(few.owner());
		RequestPostProcessor asMany = as(many.owner());
		html(get("/owner/orders").with(asFew)); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		html(get("/owner/orders").with(asFew));
		long one = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/owner/orders").with(asMany));
		long eight = stats.getPrepareStatementCount();
		// restaurant, then orders with customer, rider and dishes: nothing per order
		assertThat(eight).isEqualTo(one);
		assertThat(one).isLessThanOrEqualTo(2);

		// a board left open picks the pause up on its next poll, and loses it again on resume
		String open = currentVersion(few);
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()).with(as(few.owner())));
		String paused = mvc.perform(get("/owner/orders").param("v", open).header("HX-Request", "true").with(as(few.owner())))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(paused).startsWith(" <section id=\"owner-board\"").contains("<strong>New orders are paused.</strong>");
		String pausedVersion = currentVersion(few);
		mvc.perform(post("/owner/accepting").param("accepting", "true").with(csrf()).with(as(few.owner())));
		assertThat(mvc.perform(get("/owner/orders").param("v", pausedVersion).header("HX-Request", "true").with(as(few.owner())))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).doesNotContain("New orders are paused.");
		mvc.perform(post("/owner/accepting").param("accepting", "false").with(csrf()).with(as(few.owner())));
		assertThat(board(few)).contains("<strong>New orders are paused.</strong> Finish the ones below, then <a href=\"/owner\">turn orders back on</a>");
		assertThat(board(many)).doesNotContain("New orders are paused.");
	}

	// ---------------------------------------------------------------- accept

	@Test
	void acceptingWithAPrepTimeMovesTheOrderAndUpdatesTheEta() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PLACED);
		clock.advance(Duration.ofMinutes(5)); // 7:35 PM

		mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "30").with(csrf()).with(as(k.owner())))
			.andExpect(redirectedUrl("/owner/orders#order-" + code))
			.andExpect(flash().attribute("flashSuccess",
					"Accepted " + code + ". Ready by 8:05 PM, the customer's ETA is updated."));

		Map<String, Object> row = order(code);
		assertThat(row).containsEntry("status", "ACCEPTED");
		assertThat(row.get("accepted_at").toString()).startsWith("2026-10-04 19:35");
		assertThat(row.get("estimated_delivery_at").toString()).startsWith("2026-10-04 20:15"); // 30 min + 10 min ride
		assertThat(between(board(k), "id=\"board-cooking-title\"", "id=\"board-ready-title\""))
			.contains(code).contains("Ready by <strong>8:05 PM</strong>").contains("Start preparing");
	}

	@Test
	void everyOfferedPrepTimeWorksAndNothingElseDoes() throws Exception {
		Kitchen k = kitchen();
		for (int minutes : OwnerOrdersService.PREP_CHOICES) {
			String code = place(k, OrderStatus.PLACED);
			mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", String.valueOf(minutes))
				.with(csrf()).with(as(k.owner()))).andExpect(status().isFound());
			assertThat(order(code)).containsEntry("status", "ACCEPTED");
		}
		String code = place(k, OrderStatus.PLACED);
		for (String bad : new String[] { "25", "0", "-10" }) {
			mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", bad).with(csrf()).with(as(k.owner())))
				.andExpect(redirectedUrl("/owner/orders#order-" + code))
				.andExpect(flash().attribute("flashError", "Choose a preparation time from the list."));
		}
		mvc.perform(post("/owner/orders/" + code + "/accept").with(csrf()).with(as(k.owner())))
			.andExpect(flash().attribute("flashError", "Choose a preparation time from the list."));
		mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "soon").with(csrf()).with(as(k.owner())))
			.andExpect(status().isBadRequest());
		assertThat(order(code)).containsEntry("status", "PLACED");
		assertThat(OwnerOrdersService.defaultPrep(25)).isEqualTo(20);
		assertThat(OwnerOrdersService.defaultPrep(40)).isEqualTo(45);
		assertThat(OwnerOrdersService.defaultPrep(60)).isEqualTo(45);
		assertThat(OwnerOrdersService.defaultPrep(5)).isEqualTo(10);
	}

	@Test
	void withHtmxTheBoardIsSwappedWithAToast() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PLACED);
		String html = mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "15")
				.header("HX-Request", "true").with(csrf()).with(as(k.owner())))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).startsWith(" <section id=\"owner-board\"")
			.contains("action=\"/owner/orders/" + code + "/preparing\"")
			.contains("<div hx-swap-oob=\"beforeend:#toasts\"><div class=\"toast toast-success\"")
			.contains("Accepted " + code + ". Ready by 7:45 PM, the customer&#39;s ETA is updated.")
			.doesNotContain("<html", "new-actions-" + code);
		assertThat(order(code)).containsEntry("status", "ACCEPTED");
	}

	// ---------------------------------------------------------------- preparing and ready

	@Test
	void markPreparingThenReadyWalksTheOrderThroughTheKitchen() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.ACCEPTED);
		mvc.perform(post("/owner/orders/" + code + "/preparing").with(csrf()).with(as(k.owner())))
			.andExpect(redirectedUrl("/owner/orders#order-" + code))
			.andExpect(flash().attribute("flashSuccess", code + " is being prepared."));
		assertThat(order(code)).containsEntry("status", "PREPARING");
		assertThat(order(code).get("preparing_at")).isNotNull();
		assertThat(board(k)).contains("action=\"/owner/orders/" + code + "/ready\"").contains("Mark ready");

		mvc.perform(post("/owner/orders/" + code + "/ready").with(csrf()).with(as(k.owner())))
			.andExpect(redirectedUrl("/owner/orders#order-" + code))
			.andExpect(flash().attribute("flashSuccess", code + " is ready for pickup. A delivery partner will collect it."));
		assertThat(order(code)).containsEntry("status", "READY_FOR_PICKUP");
		assertThat(order(code).get("ready_at")).isNotNull();
		assertThat(board(k).substring(board(k).indexOf("id=\"board-ready-title\""))).contains(code)
			.contains("Waiting for a delivery partner");
	}

	@Test
	void anOrderPastItsReadyByTimeIsMarkedLate() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PREPARING); // ready by 7:50 PM
		clock.advance(Duration.ofMinutes(21));
		assertThat(board(k)).contains("<p class=\"board-order-eta is-late\">")
			.contains("Running late: was due by <strong>7:50 PM</strong>").contains(code);
	}

	@Test
	void aBoardLeftOpenTurnsLateOnTheNextPollWithoutAnyOrderChanging() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.ACCEPTED); // ready by 7:50 PM
		String v = currentVersion(k);
		clock.advance(Duration.ofMinutes(21));
		String fragment = mvc.perform(get("/owner/orders").param("v", v).header("HX-Request", "true").with(as(k.owner())))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(fragment).contains(code).contains("Running late: was due by <strong>7:50 PM</strong>");
		// and once marked late, the board is quiet again until something else changes
		mvc.perform(get("/owner/orders").param("v", currentVersion(k)).header("HX-Request", "true").with(as(k.owner())))
			.andExpect(status().isNoContent());
	}

	@Test
	void stepsOutOfOrderOrOnAMovedOnOrderChangeNothing() throws Exception {
		Kitchen k = kitchen();
		String placed = place(k, OrderStatus.PLACED);
		mvc.perform(post("/owner/orders/" + placed + "/ready").with(csrf()).with(as(k.owner())))
			.andExpect(flash().attribute("flashError", placed + " is now “Placed”, so nothing changed."));
		mvc.perform(post("/owner/orders/" + placed + "/preparing").with(csrf()).with(as(k.owner())))
			.andExpect(flash().attribute("flashError", placed + " is now “Placed”, so nothing changed."));
		assertThat(order(placed)).containsEntry("status", "PLACED");

		// the customer cancelled while the board still showed it
		String cancelled = orders.placeThenCancel(orders.customer().getId(), k.thali().getId(), 1, PaymentMethod.COD)
			.getCode();
		mvc.perform(post("/owner/orders/" + cancelled + "/accept").param("prepMinutes", "20").with(csrf()).with(as(k.owner())))
			.andExpect(flash().attribute("flashError", "The customer cancelled " + cancelled + ". Nothing to cook for it."));
		mvc.perform(post("/owner/orders/" + cancelled + "/reject").param("reason", "We're closing soon").with(csrf()).with(as(k.owner())))
			.andExpect(redirectedUrl("/owner/orders"))
			.andExpect(flash().attribute("flashError", "The customer cancelled " + cancelled + ". Nothing to cook for it."));
		String html = mvc.perform(post("/owner/orders/" + cancelled + "/accept").param("prepMinutes", "20")
				.header("HX-Request", "true").with(csrf()).with(as(k.owner())))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(html).contains("toast-error").contains("The customer cancelled " + cancelled);
		assertThat(order(cancelled)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "CUSTOMER");

		// accepting twice (a double click)
		mvc.perform(post("/owner/orders/" + placed + "/accept").param("prepMinutes", "20").with(csrf()).with(as(k.owner())));
		mvc.perform(post("/owner/orders/" + placed + "/accept").param("prepMinutes", "45").with(csrf()).with(as(k.owner())))
			.andExpect(flash().attribute("flashError", placed + " is now “Accepted”, so nothing changed."));
		assertThat(order(placed).get("estimated_delivery_at").toString()).startsWith("2026-10-04 20:00");
	}

	// ---------------------------------------------------------------- reject

	@Test
	void rejectingWithAReasonEndsTheOrderAndTheCustomerSeesWhy() throws Exception {
		Kitchen k = kitchen();
		User customer = orders.customer();
		String code = orders.place(customer.getId(), k.thali().getId(), 1, PaymentMethod.COD, OrderStatus.PLACED).getCode();

		mvc.perform(post("/owner/orders/" + code + "/reject").param("reason", "Some dishes are sold out")
				.param("details", "  No mutton left tonight ").with(csrf()).with(as(k.owner())))
			.andExpect(redirectedUrl("/owner/orders"))
			.andExpect(flash().attribute("flashSuccess", "Rejected " + code + ". The customer sees your reason on their order."));

		assertThat(order(code)).containsEntry("status", "REJECTED").containsEntry("cancelled_by", "RESTAURANT")
			.containsEntry("cancel_reason", "Some dishes are sold out: No mutton left tonight")
			.containsEntry("payment_status", "PENDING");
		assertThat(order(code).get("rejected_at")).isNotNull();
		assertThat(board(k)).doesNotContain(code);
		String track = mvc.perform(get("/orders/" + code + "/track").with(as(customer)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(track).contains("Some dishes are sold out: No mutton left tonight");
	}

	@Test
	void rejectingAPaidOrderStartsTheRefundAndGivesTheCouponBack() throws Exception {
		Kitchen k = kitchen();
		Coupon coupon = coupons.save(new Coupon("BOARD40", "₹40 off.", CouponType.FLAT, new BigDecimal("40"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User customer = orders.customer();
		String code = orders.create(customer.getId(), k.thali().getId(), 1, PaymentMethod.ONLINE).getCode();
		tx.executeWithoutResult(s -> workflow.confirmPayment(orderRepository.findForUpdateByCode(code).orElseThrow()));
		jdbc.update("INSERT INTO coupon_redemptions (coupon_id, user_id, discount, order_id, created_at, updated_at, "
				+ "version) VALUES (?, ?, 40.00, ?, ?, ?, 0)", coupon.getId(), customer.getId(), order(code).get("id"),
				MutableClock.DEFAULT_NOW, MutableClock.DEFAULT_NOW);
		assertThat(board(k)).contains("<span class=\"board-order-pay\">Paid online</span>");

		mvc.perform(post("/owner/orders/" + code + "/reject").param("reason", "Something else")
				.param("details", "Gas cylinder ran out").with(csrf()).with(as(k.owner())))
			.andExpect(flash().attribute("flashSuccess", "Rejected " + code + ". The customer gets a full refund and sees your reason."));

		assertThat(order(code)).containsEntry("status", "REJECTED").containsEntry("payment_status", "REFUND_PENDING")
			.containsEntry("cancel_reason", "Gas cylinder ran out");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isZero();
	}

	@Test
	void aRejectWithoutAReasonComesBackWithTheErrorAndTheInputKept() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PLACED);
		String other = place(k, OrderStatus.PLACED);

		String html = html(post("/owner/orders/" + code + "/reject").param("details", "<b>sorry</b>").with(csrf()).with(as(k.owner())));
		assertThat(html).contains("<title>Live orders | Zaika</title>")
			.contains("<details class=\"confirm board-reject\" data-menu open=\"open\">")
			.contains("Choose a reason. The customer sees it.")
			.contains("aria-invalid=\"true\" aria-describedby=\"reject-reason-" + code + "-error\"")
			.contains("value=\"&lt;b&gt;sorry&lt;/b&gt;\"")
			.doesNotContain("<b>sorry</b>");
		// only that order's form is open and shows the error
		assertThat(html.split("data-menu open=\"open\"", -1)).hasSize(2);
		assertThat(between(html, "id=\"order-" + other + "\"", "</article>")).doesNotContain("field-error", "sorry");

		html = html(post("/owner/orders/" + code + "/reject").param("reason", "Something else").with(csrf()).with(as(k.owner())));
		assertThat(html).contains("Tell the customer why you can&#39;t take this order.")
			.contains("<option value=\"Something else\" selected=\"selected\">Something else</option>");

		html = html(post("/owner/orders/" + code + "/reject").param("reason", "Closed for Diwali").with(csrf()).with(as(k.owner())));
		assertThat(html).contains("Choose a reason. The customer sees it.");
		html = html(post("/owner/orders/" + code + "/reject").param("reason", "We're closing soon")
			.param("details", "x".repeat(201)).with(csrf()).with(as(k.owner())));
		assertThat(html).contains("Keep it to 200 characters or fewer.");
		assertThat(order(code)).containsEntry("status", "PLACED");

		// an invalid form on an order that moved on meanwhile
		mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "20").with(csrf()).with(as(k.owner())));
		mvc.perform(post("/owner/orders/" + code + "/reject").with(csrf()).with(as(k.owner())))
			.andExpect(redirectedUrl("/owner/orders"))
			.andExpect(flash().attribute("flashError", code + " is now “Accepted”, so nothing changed."));
	}

	// ---------------------------------------------------------------- ticket

	@Test
	void theTicketIsAPrintFriendlyPageWithEverythingTheKitchenNeeds() throws Exception {
		Kitchen k = kitchen();
		User customer = orders.customer();
		String code = orders.create(customer.getId(), k.thali().getId(), 2, PaymentMethod.COD).getCode();
		tx.executeWithoutResult(s -> {
			var order = orderRepository.findForUpdateByCode(code).orElseThrow();
			order.addItem(items.findById(k.lassi().getId()).orElseThrow(), 1);
			order.setRestaurantNote("Less spicy <please>");
			workflow.transition(order, OrderStatus.PLACED, OrderActor.system());
			workflow.transition(order, OrderStatus.ACCEPTED, OrderActor.restaurant(k.owner().getId()));
		});

		String html = html(get("/owner/orders/" + code).with(as(k.owner())));
		assertThat(html).contains("<title>Ticket " + code + " | Zaika</title>")
			.contains("<p class=\"ticket-brand\">Board Kitchen</p>")
			.contains("<h1 id=\"ticket-title\" class=\"ticket-code\">" + code + "</h1>")
			.contains("<span class=\"badge badge-primary\">Accepted</span>")
			.contains("<dt>Placed</dt> <dd>4 Oct 2026, 7:30 PM</dd>")
			.contains("<dt>Ready by</dt> <dd class=\"ticket-strong\">7:50 PM</dd>")
			.contains("<dt>Customer</dt> <dd>Riya Shinde</dd>")
			.contains("<dt>Payment</dt> <dd>Cash on delivery</dd>")
			.contains("<p>Less spicy &lt;please&gt;</p>")
			.contains("<td class=\"ticket-qty\">2 ×</td>").contains("<span>Mutton Thali</span>")
			.contains("<td class=\"ticket-num\">₹500.00</td>")
			.contains("<td class=\"ticket-qty\">1 ×</td>").contains("<span>Sweet Lassi</span>")
			.contains("<span class=\"visually-hidden\">Vegetarian</span>")
			.contains("<button type=\"button\" class=\"btn btn-primary btn-sm\" data-print hidden>")
			.contains("<script defer src=\"/js/print.js\"></script>")
			.contains("<a class=\"checkout-back\" href=\"/owner/orders\">")
			.doesNotContain(customer.getPhone(), "Less spicy <please>");

		mvc.perform(get("/js/print.js")).andExpect(status().isOk());
		String rejectedCode = place(k, OrderStatus.REJECTED);
		assertThat(html(get("/owner/orders/" + rejectedCode).with(as(k.owner()))))
			.contains("<strong>You rejected this order.</strong> Reason: Kitchen closing early")
			.doesNotContain("Ready by");
	}

	@Test
	void theDashboardAndNavLinkToTheBoardAndTickets() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PLACED);
		String dashboard = html(get("/owner").with(as(k.owner())));
		assertThat(dashboard).contains("<a class=\"tab\" href=\"/owner/orders\">")
			.contains("<dd class=\"owner-stat-link\"><a href=\"/owner/orders\">Open live orders</a></dd>")
			.contains("<a class=\"btn btn-ghost btn-sm\" href=\"/owner/orders\">")
			.contains("<a href=\"/owner/orders/" + code + "\">" + code + "</a>");
		assertThat(html(get("/owner/menu").with(as(k.owner())))).contains("href=\"/owner/orders\"");
		html(get("/owner/orders/" + code).with(as(k.owner())));
	}

	// ---------------------------------------------------------------- ownership and access

	@Test
	void anotherOwnersOrdersAre404AndStayUntouched() throws Exception {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		String placed = place(theirs, OrderStatus.PLACED);
		String accepted = place(theirs, OrderStatus.ACCEPTED);
		String cooking = place(theirs, OrderStatus.PREPARING);
		RequestPostProcessor me = as(mine.owner());

		mvc.perform(get("/owner/orders/" + placed).with(me)).andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + placed + "/accept").param("prepMinutes", "20").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + placed + "/accept").param("prepMinutes", "25").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + placed + "/reject").param("reason", "We're closing soon").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + placed + "/reject").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + accepted + "/preparing").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + cooking + "/ready").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/ZK-26-999999/accept").param("prepMinutes", "20").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		assertThat(order(placed)).containsEntry("status", "PLACED");
		assertThat(order(accepted)).containsEntry("status", "ACCEPTED");
		assertThat(order(cooking)).containsEntry("status", "PREPARING");

		// an own order still awaiting payment is not the kitchen's yet
		String unpaid = orders.create(orders.customer().getId(), mine.thali().getId(), 1, PaymentMethod.ONLINE).getCode();
		mvc.perform(get("/owner/orders/" + unpaid).with(me)).andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + unpaid + "/accept").param("prepMinutes", "20").with(csrf()).with(me))
			.andExpect(status().isNotFound());
		assertThat(order(unpaid)).containsEntry("status", "PENDING_PAYMENT");
		// nor is an online order that was never paid and then cancelled (or expired)
		String neverPaid = orders.place(orders.customer().getId(), mine.thali().getId(), 1, PaymentMethod.ONLINE,
				OrderStatus.CANCELLED).getCode();
		mvc.perform(get("/owner/orders/" + neverPaid).with(me)).andExpect(status().isNotFound());
		mvc.perform(post("/owner/orders/" + neverPaid + "/reject").param("reason", RejectOrderForm.REASONS.get(0))
				.with(csrf()).with(me))
			.andExpect(status().isNotFound());
		assertThat(order(neverPaid)).containsEntry("status", "CANCELLED");
	}

	@Test
	void anOwnerWithoutARestaurantSeesAnEmptyStateAndCannotAct() throws Exception {
		User owner = users.save(new User("Lone Owner", "lone@ownerorders.zaika.test", "9876543210", "{noop}x",
				Role.RESTAURANT_OWNER));
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PLACED);
		assertThat(html(get("/owner/orders").with(as(owner)))).contains("No restaurant on this account")
			.doesNotContain("id=\"owner-board\"");
		mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "20").with(csrf()).with(as(owner)))
			.andExpect(status().isNotFound());
	}

	@Test
	void onlySignedInOwnersWithACsrfTokenGetIn() throws Exception {
		Kitchen k = kitchen();
		String code = place(k, OrderStatus.PLACED);
		mvc.perform(get("/owner/orders")).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/owner/orders/" + code)).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "20").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		User customer = users.save(new User("Customer", "cust@ownerorders.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		mvc.perform(get("/owner/orders").with(as(customer))).andExpect(status().isForbidden());
		mvc.perform(get("/owner/orders/" + code).with(as(customer))).andExpect(status().isForbidden());
		mvc.perform(post("/owner/orders/" + code + "/accept").param("prepMinutes", "20").with(csrf()).with(as(customer)))
			.andExpect(status().isForbidden());
		for (String action : new String[] { "accept", "reject", "preparing", "ready" }) {
			mvc.perform(post("/owner/orders/" + code + "/" + action).param("prepMinutes", "20")
				.param("reason", "We're closing soon").with(as(k.owner())))
				.andExpect(status().isForbidden());
		}
		assertThat(order(code)).containsEntry("status", "PLACED");
	}

	// ---------------------------------------------------------------- helpers

	private RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	private String board(Kitchen k) throws Exception {
		return html(get("/owner/orders").with(as(k.owner())));
	}

	private String currentVersion(Kitchen k) throws Exception {
		Matcher m = Pattern.compile("hx-get=\"/owner/orders\\?v=([a-z0-9-]+)\"").matcher(board(k));
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private Map<String, Object> order(String code) {
		return jdbc.queryForMap("SELECT * FROM orders WHERE code = ?", code);
	}

	private static String between(String html, String from, String to) {
		int start = html.indexOf(from);
		assertThat(start).as(from).isNotNegative();
		int end = html.indexOf(to, start);
		return end < 0 ? html.substring(start) : html.substring(start, end);
	}

	private String html(RequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

}
