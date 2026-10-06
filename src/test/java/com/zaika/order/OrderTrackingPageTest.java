package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponType;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Live order tracking: the status stepper with times, the ETA, the restaurant's contact, the rider (masked phone) once
 * assigned, the delivery code while out for delivery, htmx polling that answers 204 until something changes and
 * stops at the end states, and cancelling while PLACED (reason required, coupon given back, refund for paid orders).
 * Own database; the test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ordertrack;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderTrackingPageTest {

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
	UserRepository users;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	CouponRepository coupons;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	MutableClock clock;

	Restaurant kitchen; // ₹30 delivery, 20 min prep: ETA 8:00 PM for a 7:30 PM order

	MenuItem thali; // ₹250: one thali costs 250 + 30 + 5 + 12.50 GST = ₹297.50

	@BeforeAll
	void createRestaurant() {
		kitchen = fixtures.restaurant("track-kitchen", "Track Kitchen").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
		thali = items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false));
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	// ---------------------------------------------------------------- helpers

	RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return squash(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	String track(User me, String code) throws Exception {
		return html(get("/orders/" + code + "/track").with(as(me)));
	}

	String place(User me, OrderStatus status) {
		return orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, status).getCode();
	}

	/** The restaurant moves the order on. */
	void restaurantMoves(String code, OrderStatus target) {
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findForUpdateByCode(code).orElseThrow(),
				target, OrderActor.restaurant(kitchen.getOwner().getId())));
	}

	Map<String, Object> order(String code) {
		return jdbc.queryForMap("SELECT * FROM orders WHERE code = ?", code);
	}

	long version(String code) {
		return ((Number) order(code).get("version")).longValue();
	}

	MockHttpServletRequestBuilder cancel(User me, String code, String reason, String details) {
		MockHttpServletRequestBuilder post = post("/orders/" + code + "/cancel").with(as(me)).with(csrf());
		if (reason != null) {
			post.param("reason", reason);
		}
		if (details != null) {
			post.param("details", details);
		}
		return post;
	}

	// ---------------------------------------------------------------- the page

	@Test
	void aPlacedOrderShowsWhereItStandsTheEtaTheRestaurantAndTheCancelForm() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);

		String html = squash(mvc.perform(get("/orders/" + code + "/track").with(as(me))).andExpect(status().isOk())
			.andExpect(view().name(OrderController.TRACK_VIEW)).andExpect(header().string("Vary", "HX-Request"))
			.andReturn().getResponse().getContentAsString());

		assertThat(html).contains("<title>Track order " + code + " | Zaika</title>")
			.contains("<h1 class=\"order-title\">Track your order</h1>")
			.contains("class=\"badge order-status badge-primary\">Placed</span>")
			.contains("id=\"track-headline\">Waiting for the restaurant to accept</h2>")
			.contains("Arriving by <strong>8:00 PM</strong> <span class=\"muted\">(in 30 min)</span>")
			// the stepper: placed is current with its time, the rest still to come
			.containsPattern("class=\"step is-current\" aria-current=\"step\">.*?<span class=\"step-label\">Order placed</span>"
					+ " <span class=\"step-time\"><time datetime=\"2026-10-04T19:30\">7:30 PM</time> · <span>Waiting for Track Kitchen to accept</span>")
			.contains("<span class=\"step-label\">Accepted by the restaurant</span> </li>")
			.contains("<span class=\"step-label\">Delivered</span> </li>")
			.doesNotContain("is-done")
			// restaurant contact
			.contains("<strong>Track Kitchen</strong>").contains("12, Main Road, Rajarampuri")
			.contains("href=\"tel:+919876543210\"").contains("Call <span>+91 98765 43210</span>")
			// no rider yet, no delivery code
			.contains("We assign a delivery partner when your food is nearly ready.")
			.doesNotContain("track-rider-name").doesNotContain("track-otp")
			// the order and the way back
			.contains("<span>1 × Mutton Thali</span>").contains("₹297.50")
			.contains("href=\"/orders/" + code + "\"")
			// polling every 10 s with the version shown
			.contains("hx-get=\"/orders/" + code + "/track?v=" + version(code) + "\"")
			.contains("hx-trigger=\"every 10s\"").contains("hx-swap=\"outerHTML\"")
			.contains("id=\"track-announce\" class=\"visually-hidden\" role=\"status\"></p>")
			.contains("Refresh status")
			// cancel while placed
			.contains("action=\"/orders/" + code + "/cancel\"").contains("Why are you cancelling?")
			.contains("value=\"Ordered by mistake\"").contains("value=\"Something else\"")
			.contains("You will not be charged.").doesNotContain("<details class=\"card track-cancel\" open");
	}

	@Test
	void eachStepCarriesItsTimeAndTheCurrentOneSaysWhatIsHappening() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED); // 7:30 PM
		clock.advance(Duration.ofMinutes(3));
		restaurantMoves(code, OrderStatus.ACCEPTED); // 7:33 PM
		clock.advance(Duration.ofMinutes(4));
		restaurantMoves(code, OrderStatus.PREPARING); // 7:37 PM

		String html = track(me, code);

		assertThat(html).contains("id=\"track-headline\">Your food is being prepared</h2>")
			.contains("class=\"badge order-status badge-primary\">Preparing</span>")
			.contains("Arriving by <strong>8:00 PM</strong> <span class=\"muted\">(in 23 min)</span>")
			.containsPattern("class=\"step is-done\">.*?Order placed</span> <span class=\"step-time\"><time datetime=\"2026-10-04T19:30\">7:30 PM</time></span>")
			.containsPattern("class=\"step is-done\">.*?Accepted by the restaurant</span> <span class=\"step-time\"><time datetime=\"2026-10-04T19:33\">7:33 PM</time></span>")
			.contains("Being prepared</span> <span class=\"step-time\"><time datetime=\"2026-10-04T19:37\">7:37 PM</time> · <span>Cooked fresh for you</span>")
			.contains("<span class=\"step-label\">Ready for pickup</span> </li>")
			.doesNotContain("action=\"/orders/" + code + "/cancel\"") // accepted: too late to cancel
			.contains("hx-trigger=\"every 10s\"");

		restaurantMoves(code, OrderStatus.READY_FOR_PICKUP);
		User rider = orders.rider();
		tx.executeWithoutResult(s -> orderRepository.findForUpdateByCode(code).orElseThrow()
			.assignRider(users.findById(rider.getId()).orElseThrow()));
		assertThat(track(me, code)).contains("id=\"track-headline\">Packed and ready for pickup</h2>")
			.contains("<span>Sagar is picking it up</span>").contains("Your delivery partner")
			.contains("<p class=\"track-rider-name\">Sagar Patil</p>").doesNotContain("track-otp");
	}

	@Test
	void outForDeliveryShowsTheRiderWithAMaskedPhoneAndTheDeliveryCode() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.OUT_FOR_DELIVERY);
		String otp = (String) order(code).get("delivery_otp");

		String html = track(me, code);

		assertThat(html).contains("id=\"track-headline\">On the way to you</h2>")
			.contains("<span>Sagar is heading your way</span>")
			.contains("<p class=\"track-rider-name\">Sagar Patil</p>")
			.contains("<span aria-hidden=\"true\">+91 98••• ••345</span>")
			.contains("<span class=\"visually-hidden\">Phone number ending 345</span>")
			.doesNotContain("9822012345").doesNotContain("98220 12345")
			.contains("<span aria-hidden=\"true\">" + otp + "</span>")
			.contains("<span class=\"visually-hidden\">" + String.join(" ", otp.split("")) + "</span>")
			.contains("Tell Sagar this code at the door").contains("hx-trigger=\"every 10s\"");
	}

	@Test
	void theDeliveryCodeIsOnlyShownWhileTheOrderIsOutForDelivery() throws Exception {
		User me = orders.customer();
		for (OrderStatus status : new OrderStatus[] { OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
				OrderStatus.READY_FOR_PICKUP, OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.REJECTED }) {
			String code = place(me, status);
			assertThat(track(me, code)).as(status.name()).doesNotContain("track-otp")
				.doesNotContain("Delivery code");
		}
		// the order page never shows it either
		String out = place(me, OrderStatus.OUT_FOR_DELIVERY);
		assertThat(html(get("/orders/" + out).with(as(me)))).doesNotContain("Delivery code");
	}

	@Test
	void aLateOrderSaysSo() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PREPARING);
		clock.advance(Duration.ofMinutes(41)); // 8:11 PM, ETA was 8:00 PM

		assertThat(track(me, code)).contains("Running a little late. We expected it by <strong>8:00 PM</strong>.")
			.doesNotContain("Arriving by");
	}

	@Test
	void aDeliveredOrderStopsPolling() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.DELIVERED);

		String html = track(me, code);

		assertThat(html).contains("id=\"track-headline\">Delivered. Enjoy your meal!</h2>")
			.contains("Delivered at <strong>7:30 PM</strong>.").contains("Delivered by")
			.contains("<span class=\"step-label\">Delivered</span>").doesNotContain("is-current")
			.doesNotContain("/track?v=").doesNotContain("every 10s").doesNotContain("Refresh status")
			.doesNotContain("We assign a delivery partner").doesNotContain("/cancel\"");
		assertThat(html.split("class=\"step is-done\"", -1)).hasSize(7); // all six steps done
	}

	@Test
	void aCancelledOrderShowsTheStepsItReachedThenWhyItEnded() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);
		clock.advance(Duration.ofMinutes(2));
		mvc.perform(cancel(me, code, "Ordered by mistake", null)).andExpect(status().isFound());

		String html = track(me, code);

		assertThat(html).contains("class=\"badge order-status badge-danger\">Cancelled</span>")
			.contains("id=\"track-headline\">Order cancelled</h2>")
			.contains("<strong>You cancelled this order.</strong> <span>Reason: Ordered by mistake.</span>")
			.contains("You have not been charged.")
			.containsPattern("class=\"step is-done\">.*?Order placed</span>")
			.containsPattern("class=\"step is-failed\" aria-current=\"step\">.*?<span class=\"step-label\">Cancelled</span> <span class=\"step-time\"><time datetime=\"2026-10-04T19:32\">7:32 PM</time> · <span>Ordered by mistake</span>")
			.doesNotContain("Accepted by the restaurant").doesNotContain("every 10s").doesNotContain("Arriving by");
	}

	@Test
	void aRejectedPaidOrderShowsTheRefundAndStopsPolling() throws Exception {
		User me = orders.customer();
		String code = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();
		tx.executeWithoutResult(s -> {
			Order order = orderRepository.findForUpdateByCode(code).orElseThrow();
			workflow.confirmPayment(order);
			workflow.transition(order, OrderStatus.REJECTED, OrderActor.restaurant(kitchen.getOwner().getId()),
					"Out of mutton tonight");
		});

		assertThat(track(me, code)).contains("id=\"track-headline\">The restaurant could not take this order</h2>")
			.contains("<strong>Track Kitchen could not take this order.</strong>")
			.contains("Your refund of ₹297.50 is on its way.")
			.contains("<span class=\"step-label\">Rejected by the restaurant</span>")
			.contains("<span>Out of mutton tonight</span>").doesNotContain("every 10s");
	}

	@Test
	void anOrderAwaitingPaymentIsSentToItsPaymentPage() throws Exception {
		User me = orders.customer();
		String code = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();

		mvc.perform(get("/orders/" + code + "/track").with(as(me))).andExpect(redirectedUrl("/pay/" + code));
	}

	// ---------------------------------------------------------------- htmx polling

	@Test
	void pollingAnswersNoContentUntilTheOrderChangesThenTheNewLivePart() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);
		long shown = version(code);

		MvcResult unchanged = mvc.perform(get("/orders/" + code + "/track?v=" + shown).header("HX-Request", "true")
			.with(as(me))).andExpect(status().isNoContent()).andExpect(header().string("Vary", "HX-Request"))
			.andReturn();
		assertThat(unchanged.getResponse().getContentAsString()).isEmpty();

		clock.advance(Duration.ofMinutes(1));
		restaurantMoves(code, OrderStatus.ACCEPTED);
		String changed = squash(mvc.perform(get("/orders/" + code + "/track?v=" + shown).header("HX-Request", "true")
			.with(as(me))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(changed).doesNotContain("<html").doesNotContain("<header")
			.contains("<section id=\"track-live\"")
			.contains("hx-get=\"/orders/" + code + "/track?v=" + version(code) + "\"")
			.contains("hx-trigger=\"every 10s\"")
			.contains("id=\"track-headline\">Order accepted</h2>")
			.contains("<p id=\"track-announce\" hx-swap-oob=\"innerHTML\">Order accepted</p>")
			.doesNotContain("/cancel\""); // the cancel form goes with the change
		assertThat(version(code)).isGreaterThan(shown);

		// a history restore gets the whole page
		assertThat(html(get("/orders/" + code + "/track?v=" + version(code)).header("HX-Request", "true")
			.header("HX-History-Restore-Request", "true").with(as(me)))).contains("<html").contains("Track your order");
	}

	@Test
	void pollingStopsOnceTheOrderIsDeliveredCancelledOrRejected() throws Exception {
		User me = orders.customer();
		for (OrderStatus end : new OrderStatus[] { OrderStatus.DELIVERED, OrderStatus.CANCELLED,
				OrderStatus.REJECTED }) {
			String code = place(me, end);
			String fragment = squash(mvc.perform(get("/orders/" + code + "/track?v=0").header("HX-Request", "true")
				.with(as(me))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
			assertThat(fragment).as(end.name()).contains("<section id=\"track-live\"").doesNotContain("hx-get")
				.doesNotContain("hx-trigger").contains("hx-swap-oob");
		}
	}

	// ---------------------------------------------------------------- cancelling

	@Test
	void theCustomerCancelsAPlacedOrderWithAReason() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);

		MvcResult result = mvc.perform(cancel(me, code, "Ordered by mistake", "  Wrong dish  "))
			.andExpect(redirectedUrl("/orders/" + code + "/track"))
			.andExpect(flash().attribute("flashSuccess", "Order cancelled. You have not been charged.")).andReturn();

		assertThat(order(code)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "CUSTOMER")
			.containsEntry("cancel_reason", "Ordered by mistake: Wrong dish")
			.containsEntry("payment_status", "PENDING");
		assertThat(html(get("/orders/" + code + "/track").with(as(me)).flashAttrs(result.getFlashMap())))
			.contains("Order cancelled. You have not been charged.").contains("Reason: Ordered by mistake: Wrong dish.");
	}

	@Test
	void somethingElseTakesTheDetailsAsTheReason() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);

		mvc.perform(cancel(me, code, "Something else", "Plans changed, eating out")).andExpect(status().isFound());

		assertThat(order(code)).containsEntry("cancel_reason", "Plans changed, eating out");
	}

	@Test
	void cancellingAPaidOrderStartsTheRefund() throws Exception {
		User me = orders.customer();
		String code = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();
		tx.executeWithoutResult(s -> workflow.confirmPayment(orderRepository.findForUpdateByCode(code).orElseThrow()));
		assertThat(track(me, code)).contains("You get the full ₹297.50 back in 5-7 working days.");

		mvc.perform(cancel(me, code, "Wrong delivery address", null))
			.andExpect(flash().attribute("flashSuccess",
					"Order cancelled. Your refund of ₹297.50 reaches your account in 5-7 working days."));

		assertThat(order(code)).containsEntry("status", "CANCELLED").containsEntry("payment_status", "REFUND_PENDING");
	}

	@Test
	void cancellingGivesTheCouponUseBack() throws Exception {
		Coupon coupon = coupons.save(new Coupon("TRACK40", "₹40 off.", CouponType.FLAT, new BigDecimal("40"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);
		long orderId = ((Number) order(code).get("id")).longValue();
		jdbc.update("INSERT INTO coupon_redemptions (coupon_id, user_id, discount, order_id, created_at, updated_at, "
				+ "version) VALUES (?, ?, 40.00, ?, ?, ?, 0)", coupon.getId(), me.getId(), orderId,
				MutableClock.DEFAULT_NOW, MutableClock.DEFAULT_NOW);

		mvc.perform(cancel(me, code, "Want to change my order", null)).andExpect(status().isFound());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isZero();
	}

	@Test
	void aReasonIsRequiredAndTheFormComesBackWithTheInputKept() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);

		String missing = squash(mvc.perform(cancel(me, code, null, "Too far")).andExpect(status().isOk())
			.andExpect(view().name(OrderController.TRACK_VIEW)).andReturn().getResponse().getContentAsString());
		assertThat(missing).contains("<details class=\"card track-cancel\" open")
			.contains("<span>Choose why you are cancelling.</span>").contains(">Too far</textarea>")
			.contains("<title>Track order " + code + " | Zaika</title>");

		String other = squash(mvc.perform(cancel(me, code, "Something else", "  ")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(other).contains("<span>Tell us why you are cancelling.</span>")
			.containsPattern("value=\"Something else\" checked=\"checked\"|checked=\"checked\" value=\"Something else\"|value=\"Something else\" checked")
			.contains("aria-invalid=\"true\"");

		String unknown = squash(mvc.perform(cancel(me, code, "Because", null)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(unknown).contains("<span>Choose why you are cancelling.</span>");

		String tooLong = squash(mvc.perform(cancel(me, code, "Ordered by mistake", "x".repeat(201)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(tooLong).contains("<span>Keep it to 200 characters or fewer.</span>");

		assertThat(order(code)).containsEntry("status", "PLACED");
	}

	@Test
	void onceTheRestaurantAcceptsItIsTooLateToCancel() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.ACCEPTED);

		mvc.perform(cancel(me, code, "Ordered by mistake", null)).andExpect(redirectedUrl("/orders/" + code + "/track"))
			.andExpect(flash().attribute("flashError",
					"Too late to cancel: this order is now “Accepted”. Need help with it? Contact us."));
		// an invalid form on an order that moved on says the same, rather than showing a form that cannot work
		mvc.perform(cancel(me, code, null, null)).andExpect(redirectedUrl("/orders/" + code + "/track"))
			.andExpect(flash().attribute("flashError",
					"Too late to cancel: this order is now “Accepted”. Need help with it? Contact us."));
		assertThat(order(code)).containsEntry("status", "ACCEPTED").containsEntry("cancel_reason", null);

		String unpaid = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();
		mvc.perform(cancel(me, unpaid, "Ordered by mistake", null)).andExpect(flash().attributeExists("flashError"));
		assertThat(order(unpaid)).containsEntry("status", "PENDING_PAYMENT");
	}

	@Test
	void cancellingTwiceSaysItIsAlreadyCancelled() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);
		mvc.perform(cancel(me, code, "Ordered by mistake", null)).andExpect(status().isFound());

		mvc.perform(cancel(me, code, "Ordered by mistake", null))
			.andExpect(flash().attribute("flashInfo", "This order is already cancelled."));
	}

	// ---------------------------------------------------------------- who may see and cancel

	@Test
	void onlyTheOrdersCustomerTracksOrCancelsIt() throws Exception {
		User me = orders.customer();
		User other = orders.customer();
		String code = place(me, OrderStatus.PLACED);

		mvc.perform(get("/orders/" + code + "/track").with(as(other))).andExpect(status().isNotFound());
		mvc.perform(get("/orders/" + code + "/track").header("HX-Request", "true").with(as(other)))
			.andExpect(status().isNotFound());
		mvc.perform(cancel(other, code, "Ordered by mistake", null)).andExpect(status().isNotFound());
		mvc.perform(get("/orders/ZK-26-999999/track").with(as(me))).andExpect(status().isNotFound());
		mvc.perform(get("/orders/" + code + "/track").with(anonymous())).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/orders/" + code + "/cancel").with(anonymous()).with(csrf()).param("reason",
				"Ordered by mistake")).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/orders/" + code + "/track").with(as(kitchen.getOwner()))).andExpect(status().isForbidden());
		mvc.perform(post("/orders/" + code + "/cancel").with(as(kitchen.getOwner())).with(csrf())
			.param("reason", "Ordered by mistake")).andExpect(status().isForbidden());
		mvc.perform(post("/orders/" + code + "/cancel").with(as(me)).param("reason", "Ordered by mistake"))
			.andExpect(status().isForbidden()); // no CSRF token

		assertThat(order(code)).containsEntry("status", "PLACED");
	}

	// ---------------------------------------------------------------- links to the page

	@Test
	void liveOrdersLinkToTheirTrackingPage() throws Exception {
		User me = orders.customer();
		String live = place(me, OrderStatus.PREPARING);
		clock.advance(Duration.ofMinutes(1));
		String delivered = place(me, OrderStatus.DELIVERED);
		clock.advance(Duration.ofMinutes(1));
		String unpaid = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();

		String list = html(get("/orders").with(as(me)));
		assertThat(list).contains("href=\"/orders/" + live + "/track\">Track")
			.doesNotContain("/orders/" + delivered + "/track").doesNotContain("/orders/" + unpaid + "/track");
		assertThat(html(get("/orders/" + live).with(as(me)))).contains("href=\"/orders/" + live + "/track\">Track order</a>");
		assertThat(html(get("/orders/" + delivered).with(as(me)))).doesNotContain(delivered + "/track");
	}

	@Test
	void theConfirmationLinksToTracking() throws Exception {
		User me = orders.customer();
		String code = place(me, OrderStatus.PLACED);

		String html = html(get("/orders/" + code).with(as(me)).flashAttr(OrderController.PLACED, true));

		assertThat(html).contains("id=\"order-confirmed-title\">Order placed</h2>")
			.contains("href=\"/orders/" + code + "/track\">").contains("Track your order</a>")
			.doesNotContain(">Track order</a>"); // one tracking button, not two
	}

	// ---------------------------------------------------------------- queries

	@Test
	void theTrackingPageAndAPollRunAFixedNumberOfQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		User me = orders.customer();
		String code = place(me, OrderStatus.OUT_FOR_DELIVERY);
		RequestPostProcessor asMe = as(me);
		track(me, code); // warm up

		stats.clear();
		html(get("/orders/" + code + "/track").with(asMe));
		long page = stats.getPrepareStatementCount();
		stats.clear();
		mvc.perform(get("/orders/" + code + "/track?v=" + version(code)).header("HX-Request", "true").with(asMe))
			.andExpect(status().isNoContent());
		long poll = stats.getPrepareStatementCount();

		// cart count, then the order with restaurant, rider and dishes
		assertThat(page).isEqualTo(2);
		assertThat(poll).isEqualTo(1);
	}

}
