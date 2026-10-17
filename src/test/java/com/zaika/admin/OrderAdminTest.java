package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponType;
import com.zaika.payment.PaymentChannel;
import com.zaika.payment.PaymentForm;
import com.zaika.payment.PaymentService;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T42: orders for the admin ({@code /admin/orders}): the filters (code, status, restaurant, checkout days) with
 * canonical URLs, one order with its full timeline (payment attempts included, phones masked, no delivery code), and
 * the admin cancel with a reason (coupon use given back, paid online orders to REFUND_PENDING). The list is
 * platform-wide and the tests share one database, so each test filters by its own restaurant.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:adminorders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderAdminTest {

	static final LocalDateTime NOW = MutableClock.DEFAULT_NOW;

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
	PaymentService payments;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	CouponRepository coupons;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	MutableClock clock;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** A restaurant of its own and its one dish's id. */
	record Kitchen(Restaurant restaurant, long dish) {

		String slug() {
			return restaurant.getSlug();
		}

	}

	Kitchen kitchen(String name) {
		Restaurant r = fixtures.restaurant("ord-" + UUID.randomUUID().toString().substring(0, 8), name)
			.dishes("Misal Pav")
			.save();
		return new Kitchen(r, items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(r.getId()).get(0).getId());
	}

	Order place(Kitchen k, PaymentMethod method, OrderStatus target) {
		return orders.place(orders.customer().getId(), k.dish(), 1, method, target);
	}

	Order reload(Order o) {
		return orderRepository.findById(o.getId()).orElseThrow();
	}

	/** Moves the order on as {@code actor} in its own transaction. */
	void move(Order o, OrderStatus target, OrderActor actor) {
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findById(o.getId()).orElseThrow(), target, actor));
	}

	static PaymentForm upi(boolean fail) {
		PaymentForm form = new PaymentForm();
		form.setChannel(PaymentChannel.UPI);
		form.setUpiId("riya.shinde@okzaika");
		form.setSimulateFailure(fail);
		return form;
	}

	User admin() {
		return users.save(new User("Aditi Kulkarni", "admin-" + UUID.randomUUID() + "@zaika.test", "9890011223",
				"hash", Role.ADMIN));
	}

	RequestPostProcessor as(User user) {
		return user(userDetails.loadUserByUsername(user.getEmail()));
	}

	RequestPostProcessor asAdmin() {
		return as(admin());
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.with(asAdmin()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	String list(String query) throws Exception {
		return html(get(URI.create("/admin/orders" + query)));
	}

	String page(Order o) throws Exception {
		return html(get("/admin/orders/" + o.getCode()));
	}

	MockHttpServletRequestBuilder cancel(Order o, String reason) {
		return post("/admin/orders/" + o.getCode() + "/cancel").param("reason", reason).with(csrf());
	}

	// ---------------------------------------------------------------- list and filters

	@Test
	void theListShowsEveryOrderNewestCheckoutFirstWithItsDetails() throws Exception {
		Kitchen k = kitchen("Kolhapur <Misal> House");
		Order older = place(k, PaymentMethod.COD, OrderStatus.DELIVERED);
		clock.advance(Duration.ofMinutes(5));
		Order unpaid = place(k, PaymentMethod.ONLINE, OrderStatus.PENDING_PAYMENT);
		clock.advance(Duration.ofMinutes(5));
		Order newer = place(k, PaymentMethod.COD, OrderStatus.PREPARING);

		String body = list("?restaurant=" + k.slug());
		assertThat(body).contains("<title>Orders | Zaika</title>")
			.contains("href=\"/admin/orders\" aria-current=\"page\"")
			.contains("Showing 1–3 of 3 orders, newest first")
			.contains("Kolhapur &lt;Misal&gt; House")
			.doesNotContain("Kolhapur <Misal> House")
			.contains("href=\"/admin/orders/" + newer.getCode() + "\"");
		assertThat(body.indexOf(newer.getCode())).isLessThan(body.indexOf(unpaid.getCode()));
		assertThat(body.indexOf(unpaid.getCode())).isLessThan(body.indexOf(older.getCode()));
		assertThat(body).contains(">Preparing</span>").contains(">Awaiting payment</span>").contains(">Delivered</span>")
			.contains(">Online</span> <span class=\"admin-restaurant-meta\">Pending</span>")
			.contains(">Cash</span> <span class=\"admin-restaurant-meta\">Paid</span>")
			.contains("4 Oct 2026, 7:40 PM")
			// the restaurant filter keeps its choice
			.contains("<option value=\"" + k.slug() + "\" selected=\"selected\">Kolhapur &lt;Misal&gt; House</option>")
			.contains("Clear filters");
	}

	@Test
	void ordersAreFoundByPartOfTheirCodeInAnyCase() throws Exception {
		Kitchen k = kitchen("Code Kitchen");
		Order wanted = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		Order other = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		String digits = wanted.getCode().substring(6); // "000123"

		String byFullCode = list("?q=" + wanted.getCode().toLowerCase());
		assertThat(byFullCode).contains("One order").contains(wanted.getCode()).doesNotContain(other.getCode())
			.contains("value=\"" + wanted.getCode().toLowerCase() + "\"");
		assertThat(list("?q=" + digits + "&restaurant=" + k.slug())).contains(wanted.getCode())
			.doesNotContain(other.getCode());
		// LIKE wildcards are literal
		assertThat(list("?q=%25&restaurant=" + k.slug())).contains("No orders match these filters")
			.doesNotContain(wanted.getCode());
	}

	@Test
	void theStatusAndRestaurantFiltersCombine() throws Exception {
		Kitchen k = kitchen("Status Kitchen");
		Kitchen elsewhere = kitchen("Elsewhere Kitchen");
		Order placed = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		Order delivered = place(k, PaymentMethod.COD, OrderStatus.DELIVERED);
		Order rejected = place(k, PaymentMethod.COD, OrderStatus.REJECTED);
		Order unpaid = place(k, PaymentMethod.ONLINE, OrderStatus.PENDING_PAYMENT);
		Order otherPlaced = place(elsewhere, PaymentMethod.COD, OrderStatus.PLACED);

		String body = list("?status=placed&restaurant=" + k.slug());
		assertThat(body).contains(placed.getCode())
			.doesNotContain(delivered.getCode())
			.doesNotContain(rejected.getCode())
			.doesNotContain(otherPlaced.getCode())
			.contains("<option value=\"placed\" selected=\"selected\">Placed</option>");
		assertThat(list("?status=pending-payment&restaurant=" + k.slug())).contains(unpaid.getCode())
			.doesNotContain(placed.getCode());
		assertThat(list("?status=placed")).contains(placed.getCode()).contains(otherPlaced.getCode());
		assertThat(list("?status=out-for-delivery&restaurant=" + k.slug())).contains("No orders match these filters")
			.contains("Try part of the code, another status, or a wider range of days.");
	}

	@Test
	void theDateRangeCoversWholeCheckoutDaysAtBothEnds() throws Exception {
		Kitchen k = kitchen("Dated Kitchen");
		clock.setTo(LocalDateTime.of(2026, 10, 1, 0, 0));
		Order firstMinute = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		clock.setTo(LocalDateTime.of(2026, 9, 30, 23, 59));
		Order dayBefore = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		clock.setTo(LocalDateTime.of(2026, 10, 3, 23, 59));
		Order lastMinute = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		clock.setTo(LocalDateTime.of(2026, 10, 4, 0, 0));
		Order dayAfter = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		clock.reset();

		String range = list("?restaurant=" + k.slug() + "&from=2026-10-01&to=2026-10-03");
		assertThat(range).contains(firstMinute.getCode()).contains(lastMinute.getCode())
			.doesNotContain(dayBefore.getCode()).doesNotContain(dayAfter.getCode())
			.contains("value=\"2026-10-01\"").contains("value=\"2026-10-03\"");
		assertThat(list("?restaurant=" + k.slug() + "&from=2026-10-04")).contains(dayAfter.getCode())
			.doesNotContain(lastMinute.getCode());
		assertThat(list("?restaurant=" + k.slug() + "&to=2026-09-30")).contains(dayBefore.getCode())
			.doesNotContain(firstMinute.getCode());
	}

	@Test
	void everyOtherSpellingOfAQueryRedirectsToItsCanonicalUrl() throws Exception {
		Kitchen k = kitchen("Canonical Kitchen");
		place(k, PaymentMethod.COD, OrderStatus.PLACED);
		String r = "restaurant=" + k.slug();
		String[][] cases = {
				{ "?q=&status=&restaurant=&from=&to=", "/admin/orders" },
				{ "?status=bogus", "/admin/orders" },
				{ "?status=PLACED", "/admin/orders?status=placed" },
				{ "?status=%20placed%20", "/admin/orders?status=placed" },
				{ "?restaurant=no-such-kitchen", "/admin/orders" },
				{ "?from=yesterday&to=2026-13-45", "/admin/orders" },
				{ "?from=1999-12-31&to=2099-01-01", "/admin/orders" },
				{ "?from=2026-10-03&to=2026-10-01", "/admin/orders?from=2026-10-01&to=2026-10-03" },
				{ "?q=%20zk-26%20", "/admin/orders?q=zk-26" },
				{ "?" + r + "&page=1", "/admin/orders?" + r },
				{ "?" + r + "&page=abc", "/admin/orders?" + r },
				{ "?" + r + "&page=9", "/admin/orders?" + r },
				{ "?to=2026-10-04&" + r + "&status=placed&q=ZK", "/admin/orders?q=ZK&status=placed&" + r + "&to=2026-10-04" },
				{ "?" + r + "&utm=x", "/admin/orders?" + r } };
		User admin = admin();
		for (String[] c : cases) {
			mvc.perform(get(URI.create("/admin/orders" + c[0])).with(as(admin)))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl(c[1]));
		}
	}

	@Test
	void theListPagesTwentyAtATimeWithAFixedNumberOfQueries() throws Exception {
		Kitchen k = kitchen("Busy Kitchen");
		List<Order> placed = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			placed.add(place(k, PaymentMethod.COD, OrderStatus.PLACED));
			clock.advance(Duration.ofMinutes(1));
		}
		String first = list("?restaurant=" + k.slug());
		assertThat(first).contains("Showing 1–20 of 21 orders, newest first")
			.contains("href=\"/admin/orders?restaurant=" + k.slug() + "&amp;page=2\"")
			.doesNotContain(placed.get(0).getCode() + "<");
		assertThat(list("?restaurant=" + k.slug() + "&page=2")).contains("Showing 21–21 of 21 orders")
			.contains(placed.get(0).getCode());

		RequestPostProcessor signedIn = asAdmin();
		String url = "/admin/orders?restaurant=" + k.slug();
		mvc.perform(get(URI.create(url)).with(signedIn)).andExpect(status().isOk()); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		mvc.perform(get(URI.create(url)).with(signedIn)).andExpect(status().isOk());
		long before = stats.getPrepareStatementCount();
		for (int i = 0; i < 3; i++) {
			orders.place(orders.customer().getId(), k.dish(), 2, PaymentMethod.ONLINE,
					OrderStatus.DELIVERED);
		}
		stats.clear();
		mvc.perform(get(URI.create(url)).with(signedIn)).andExpect(status().isOk());
		// restaurants for the filter, the page with restaurant and customer, the count: never one per order
		assertThat(stats.getPrepareStatementCount()).isEqualTo(before);
	}

	@Test
	void anEmptyListSaysSo() throws Exception {
		Kitchen k = kitchen("Quiet Kitchen");
		assertThat(list("?restaurant=" + k.slug())).contains("No orders match these filters")
			.doesNotContain("<table");
	}

	// ---------------------------------------------------------------- the order page

	@Test
	void anOrderPageShowsTheFullTimelineWithEveryPaymentAttempt() throws Exception {
		Kitchen k = kitchen("Timeline Kitchen");
		User customer = orders.customer();
		Order o = orders.create(customer.getId(), k.dish(), 2, PaymentMethod.ONLINE);
		clock.advance(Duration.ofMinutes(2));
		payments.pay(customer.getId(), o.getCode(), upi(true));
		clock.advance(Duration.ofMinutes(1));
		payments.pay(customer.getId(), o.getCode(), upi(false));
		long owner = k.restaurant().getOwner().getId();
		clock.advance(Duration.ofMinutes(4));
		move(o, OrderStatus.ACCEPTED, OrderActor.restaurant(owner));
		clock.advance(Duration.ofMinutes(1));
		move(o, OrderStatus.PREPARING, OrderActor.restaurant(owner));
		clock.advance(Duration.ofMinutes(15));
		move(o, OrderStatus.READY_FOR_PICKUP, OrderActor.restaurant(owner));
		User rider = orders.rider();
		tx.executeWithoutResult(s -> orderRepository.findById(o.getId()).orElseThrow()
			.assignRider(users.findById(rider.getId()).orElseThrow()));
		clock.advance(Duration.ofMinutes(50));
		move(o, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(rider.getId()));
		clock.advance(Duration.ofMinutes(10));
		move(o, OrderStatus.DELIVERED, OrderActor.rider(rider.getId()));

		String body = page(o);
		assertThat(body).contains("<title>Order " + o.getCode() + " | Zaika</title>")
			.contains("<h1 class=\"owner-title admin-code\">" + o.getCode() + "</h1>")
			.contains(">Online: Paid</span>")
			.containsSubsequence("Checked out", "4 Oct 2026, 7:30 PM", "at checkout", "2 items",
					"Payment failed", "+2 min", "UPI · ri••@okzaika", "declined",
					"Paid online", "+3 min", "UPI · ri••@okzaika · MOCK-",
					"Sent to Timeline Kitchen", "Paid online",
					"Accepted by the restaurant", "+7 min",
					"Being prepared", "+8 min",
					"Ready for pickup", "+23 min",
					"Out for delivery", "+1 h 13 min", "Picked up by Sagar Patil",
					"Delivered", "4 Oct 2026, 8:53 PM", "+1 h 23 min")
			.doesNotContain("is-current")
			.doesNotContain("Expected delivery")
			// people, with masked phones and links
			.contains("href=\"/admin/users/" + customer.getId() + "\">Riya Shinde</a>")
			.contains("href=\"/admin/restaurants/" + k.slug() + "\">Timeline Kitchen</a>")
			.contains("href=\"/admin/riders/" + rider.getId() + "\">Sagar Patil</a>")
			.contains("+91 98••• ••210").contains("+91 98••• ••345")
			.doesNotContain("9876543210").doesNotContain("98765 43210").doesNotContain("9822012345")
			.contains("Flat 4B, Shree Apartments")
			.contains("Online (test mode)")
			.containsSubsequence("Paid with", "UPI · ri••@okzaika", "MOCK-")
			// nothing to cancel any more
			.doesNotContain("Cancel this order")
			.doesNotContain("/cancel\"");
		Order done = reload(o);
		assertThat(body).doesNotContain(">" + done.getDeliveryOtp() + "<").doesNotContain("Delivery code");
	}

	@Test
	void aLiveOrderShowsWhereItStandsAndWhenItIsDue() throws Exception {
		Kitchen k = kitchen("Live Kitchen");
		Order o = place(k, PaymentMethod.COD, OrderStatus.PREPARING);
		String body = page(o);
		assertThat(body).containsSubsequence("Sent to Live Kitchen", "Cash on delivery", "Accepted by the restaurant",
				"class=\"step is-current\" aria-current=\"step\"", "Being prepared", "Expected delivery", "By ")
			.contains("Not assigned yet")
			.contains("Cancel this order")
			.contains("Nothing was charged, so there is nothing to refund.");

		clock.advance(Duration.ofHours(2));
		assertThat(page(o)).contains("Running late").contains("Was due ");
	}

	@Test
	void anEndedOrderShowsWhoEndedItAndWhy() throws Exception {
		Kitchen k = kitchen("Ended <Kitchen>");
		Order rejected = place(k, PaymentMethod.COD, OrderStatus.REJECTED);
		assertThat(page(rejected)).containsSubsequence("Sent to Ended &lt;Kitchen&gt;", "class=\"step is-failed\"",
				"Rejected by Ended &lt;Kitchen&gt;", "Kitchen closing early");
		Order cancelled = orders.placeThenCancel(orders.customer().getId(), k.dish(), 1, PaymentMethod.COD);
		assertThat(page(cancelled)).contains("Cancelled by the customer").contains("Ordered by mistake")
			.doesNotContain("Cancel this order");
	}

	@Test
	void unknownOrdersAreNotFound() throws Exception {
		User admin = admin();
		mvc.perform(get("/admin/orders/ZK-26-999999").with(as(admin))).andExpect(status().isNotFound());
		mvc.perform(post("/admin/orders/ZK-26-999999/cancel").param("reason", "Duplicate").with(as(admin)).with(csrf()))
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- cancel

	@Test
	void cancellingACashOrderGivesTheCouponUseBackAndTellsTheCustomer() throws Exception {
		Kitchen k = kitchen("Coupon Kitchen");
		User customer = orders.customer();
		Order o = orders.place(customer.getId(), k.dish(), 1, PaymentMethod.COD, OrderStatus.OUT_FOR_DELIVERY);
		long couponId = coupons.save(new Coupon("T42CANCEL", "Test offer", CouponType.FLAT, new BigDecimal("20"),
				NOW.minusDays(1))).getId();
		jdbc.update("""
				INSERT INTO coupon_redemptions (coupon_id, user_id, order_id, discount, created_at, updated_at, version)
				VALUES (?, ?, ?, 20.00, ?, ?, 0)""", couponId, customer.getId(), o.getId(), NOW, NOW);
		jdbc.update("UPDATE orders SET coupon_code = 'T42CANCEL' WHERE id = ?", o.getId());
		long versionBefore = reload(o).getVersion();

		assertThat(page(o)).contains("The T42CANCEL use is given back.").contains("Why cancel it?");
		User admin = admin();
		clock.advance(Duration.ofMinutes(3));
		mvc.perform(cancel(o, "  Rider had an accident; customer informed  ").with(as(admin)))
			.andExpect(redirectedUrl("/admin/orders/" + o.getCode()))
			.andExpect(flash().attribute("flashSuccess",
					"Order " + o.getCode() + " is cancelled. Nothing was charged, so there is nothing to refund."));

		Order cancelled = reload(o);
		assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.getCancelledBy()).isEqualTo(OrderActor.Kind.ADMIN);
		assertThat(cancelled.getCancelReason()).isEqualTo("Rider had an accident; customer informed");
		assertThat(cancelled.timestampOf(OrderStatus.CANCELLED)).isEqualTo(LocalDateTime.now(clock));
		assertThat(cancelled.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(cancelled.getVersion()).isGreaterThan(versionBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE order_id = ?", Long.class,
				o.getId())).isZero();

		assertThat(page(o)).containsSubsequence("Out for delivery", "Cancelled by Zaika support", "+3 min",
				"Rider had an accident; customer informed").doesNotContain("Cancel this order");
		String customerPage = mvc.perform(get("/orders/" + o.getCode()).with(as(customer)))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(customerPage).contains("Zaika support cancelled this order.")
			.contains("Reason: Rider had an accident; customer informed.")
			.contains("You have not been charged.");
	}

	@Test
	void cancellingAPaidOnlineOrderMovesItToRefundPending() throws Exception {
		Kitchen k = kitchen("Refund Kitchen");
		User customer = orders.customer();
		Order o = orders.create(customer.getId(), k.dish(), 1, PaymentMethod.ONLINE);
		payments.pay(customer.getId(), o.getCode(), upi(false));
		move(o, OrderStatus.ACCEPTED, OrderActor.restaurant(k.restaurant().getOwner().getId()));
		String total = reload(o).getBill().grandTotal().toPlainString();
		assertThat(page(o)).contains("It was paid online, so it moves to refund pending for ₹" + total + ".");

		mvc.perform(cancel(o, "Kitchen fire, restaurant closed for the night").with(asAdmin()))
			.andExpect(flash().attribute("flashSuccess",
					"Order " + o.getCode() + " is cancelled. Its refund of ₹" + total + " is now pending."));
		Order cancelled = reload(o);
		assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
		assertThat(page(o)).contains(">Online: Refund in progress</span>");
		String customerPage = mvc.perform(get("/orders/" + o.getCode()).with(as(customer)))
			.andReturn().getResponse().getContentAsString();
		assertThat(customerPage).contains("Your refund of ₹" + total + " is on its way.");
	}

	@Test
	void anUnpaidOnlineOrderCanBeCancelledWithNothingToRefund() throws Exception {
		Kitchen k = kitchen("Unpaid Kitchen");
		Order o = place(k, PaymentMethod.ONLINE, OrderStatus.PENDING_PAYMENT);
		assertThat(page(o)).contains("The customer has not paid yet.");
		mvc.perform(cancel(o, "Customer asked support to cancel").with(asAdmin()))
			.andExpect(flash().attribute("flashSuccess",
					"Order " + o.getCode() + " is cancelled. Nothing was charged, so there is nothing to refund."));
		assertThat(reload(o).getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(reload(o).getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	void endedOrdersCannotBeCancelled() throws Exception {
		Kitchen k = kitchen("Done Kitchen");
		User admin = admin();
		for (OrderStatus end : new OrderStatus[] { OrderStatus.DELIVERED, OrderStatus.REJECTED, OrderStatus.CANCELLED }) {
			Order o = place(k, PaymentMethod.COD, end);
			long version = reload(o).getVersion();
			String refusal = o.getCode() + switch (end) {
				case DELIVERED -> " was already delivered, so it can't be cancelled.";
				case REJECTED -> " was already rejected by the restaurant, so there is nothing to cancel.";
				default -> " is already cancelled.";
			};
			mvc.perform(cancel(o, "Duplicate order").with(as(admin)))
				.andExpect(redirectedUrl("/admin/orders/" + o.getCode()))
				.andExpect(flash().attribute("flashError", refusal));
			// a missing reason on an ended order gets the same refusal, not the form
			mvc.perform(cancel(o, " ").with(as(admin)))
				.andExpect(redirectedUrl("/admin/orders/" + o.getCode()))
				.andExpect(flash().attribute("flashError", refusal));
			Order after = reload(o);
			assertThat(after.getStatus()).isEqualTo(end);
			assertThat(after.getVersion()).isEqualTo(version);
		}
	}

	@Test
	void aCancelNeedsAReasonOfAtMost500Characters() throws Exception {
		Kitchen k = kitchen("Reason Kitchen");
		Order o = place(k, PaymentMethod.COD, OrderStatus.PLACED);
		User admin = admin();
		String blank = mvc.perform(cancel(o, "   ").with(as(admin)))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/order"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(blank).contains("<details class=\"confirm admin-reason\" data-menu open=\"open\">")
			.contains("Write a reason. The customer sees it on their order.")
			.contains("aria-invalid=\"true\"");

		String tooLong = "<b>" + "x".repeat(500) + "</b>";
		String longBody = mvc.perform(cancel(o, tooLong).with(as(admin)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(longBody).contains("Keep the reason under 500 characters.")
			.contains("&lt;b&gt;xxx").doesNotContain("<b>xxx");
		assertThat(reload(o).getStatus()).isEqualTo(OrderStatus.PLACED);
		// a reason of exactly 500 characters is fine
		mvc.perform(cancel(o, "y".repeat(500)).with(as(admin))).andExpect(flash().attributeExists("flashSuccess"));
		assertThat(reload(o).getCancelReason()).hasSize(500);
	}

	// ---------------------------------------------------------------- links and security

	@Test
	void theDashboardAndAccountPagesLinkToTheOrderPage() throws Exception {
		Kitchen k = kitchen("Linked Kitchen");
		User customer = orders.customer();
		clock.setTo(NOW.plusHours(6)); // the newest placed order, so the dashboard's recent list has it
		Order o = orders.place(customer.getId(), k.dish(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		String link = "<a class=\"admin-code\" href=\"/admin/orders/" + o.getCode() + "\">" + o.getCode() + "</a>";
		assertThat(html(get("/admin"))).contains(link).contains("href=\"/admin/orders\">All orders");
		assertThat(html(get("/admin/users/" + customer.getId()))).contains(link);
	}

	@Test
	void onlyAdminsReachOrders() throws Exception {
		Kitchen k = kitchen("Guarded Kitchen");
		User customer = orders.customer();
		Order o = orders.place(customer.getId(), k.dish(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		String[] pages = { "/admin/orders", "/admin/orders/" + o.getCode() };
		for (String url : pages) {
			mvc.perform(get(url)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		}
		mvc.perform(post("/admin/orders/" + o.getCode() + "/cancel").param("reason", "x").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		User owner = users.findById(k.restaurant().getOwner().getId()).orElseThrow();
		for (User someone : new User[] { customer, owner, orders.rider() }) {
			for (String url : pages) {
				mvc.perform(get(url).with(as(someone))).andExpect(status().isForbidden());
			}
			mvc.perform(cancel(o, "Not mine to cancel").with(as(someone))).andExpect(status().isForbidden());
		}
		mvc.perform(post("/admin/orders/" + o.getCode() + "/cancel").param("reason", "No token").with(asAdmin()))
			.andExpect(status().isForbidden());
		assertThat(reload(o).getStatus()).isEqualTo(OrderStatus.PLACED);
	}

}
