package com.zaika.partner;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.common.Formatters;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.RiderEarning;
import com.zaika.delivery.RiderEarningRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T36: the delivery flow on the rider dashboard. "Picked up" works only once the kitchen has the order ready
 * (READY_FOR_PICKUP -> OUT_FOR_DELIVERY); "Delivered" needs the customer's 4-digit code, and five wrong codes in a row
 * lock it for ten minutes. On delivery a cash order is PAID and the rider's earning (base payout + share of the
 * delivery fee) is recorded with the cash collected. The clock stands at Sunday 4 Oct 2026, 7:30 PM.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:deliveryflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DeliveryFlowTest {

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
	DeliveryService deliveries;

	@Autowired
	RiderEarningRepository earnings;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	MutableClock clock;

	@Autowired
	Formatters fmt;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** A kitchen with a ₹250 thali and a ₹30 delivery fee: the rider earns ₹30 + 80% of ₹30 = ₹54 per delivery. */
	record Kitchen(Restaurant restaurant, MenuItem thali) {
	}

	Kitchen kitchen(String name) {
		String slug = "flow-" + UUID.randomUUID().toString().substring(0, 8);
		Restaurant r = fixtures.restaurant(slug, name).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		return new Kitchen(r, items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false)));
	}

	User customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@flow.zaika.test", "9123456789", "hash",
				Role.CUSTOMER));
	}

	/** An approved rider who is online. */
	User rider() {
		User rider = orders.rider();
		DeliveryPartnerProfile profile = new DeliveryPartnerProfile(rider, VehicleType.BIKE, "MH09AB1234",
				"Rajarampuri");
		profile.changeStatus(RiderStatus.APPROVED, null);
		profile.setOnline(true);
		profiles.save(profile);
		return rider;
	}

	/** One thali at {@code status} (ACCEPTED, PREPARING or READY_FOR_PICKUP), claimed by {@code rider}. */
	Order claimed(Kitchen k, User rider, PaymentMethod method, OrderStatus status) {
		Order order = orders.place(customer().getId(), k.thali().getId(), 1, method, status);
		deliveries.claim(rider.getId(), order.getCode());
		return reload(order);
	}

	/** The same, picked up: OUT_FOR_DELIVERY. */
	Order onTheWay(Kitchen k, User rider, PaymentMethod method) {
		Order order = claimed(k, rider, method, OrderStatus.READY_FOR_PICKUP);
		deliveries.pickUp(rider.getId(), order.getCode());
		return reload(order);
	}

	Order reload(Order order) {
		return tx.execute(s -> {
			Order o = orderRepository.findById(order.getId()).orElseThrow();
			o.getRestaurant().getName();
			return o;
		});
	}

	RequestPostProcessor as(User user) {
		return user(userDetails.loadUserByUsername(user.getEmail()));
	}

	String page(User rider) throws Exception {
		return squash(mvc.perform(get("/partner").with(as(rider))).andExpect(status().isOk()));
	}

	static String squash(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	ResultActions pickUp(User rider, Order order) throws Exception {
		return mvc.perform(post("/partner/orders/" + order.getCode() + "/pickup").with(csrf()).with(as(rider)));
	}

	ResultActions deliver(User rider, Order order, String otp) throws Exception {
		return mvc.perform(post("/partner/orders/" + order.getCode() + "/deliver").param("otp", otp).with(csrf())
			.with(as(rider)));
	}

	static String wrongCode(Order order) {
		return order.getDeliveryOtp().equals("0000") ? "1111" : "0000";
	}

	// ---------------------------------------------------------------- picked up

	@Test
	void pickedUpOnlyOnceTheKitchenHasTheOrderReady() throws Exception {
		Kitchen k = kitchen("Pickup Kitchen");
		User rider = rider();
		Order order = claimed(k, rider, PaymentMethod.COD, OrderStatus.PREPARING);
		long owner = k.restaurant().getOwner().getId();

		// still cooking: no button, a hint, and the card polls for the kitchen's move
		String cooking = page(rider);
		assertThat(cooking).contains("You can mark it picked up once the kitchen has packed it.")
			.contains("hx-get=\"/partner?delivery=" + order.getCode() + ":" + order.getVersion() + "\"")
			.contains("hx-trigger=\"every 15s\"")
			.doesNotContain("id=\"rider-pickup\"")
			.doesNotContain("name=\"otp\"");
		pickUp(rider, order).andExpect(redirectedUrl("/partner#current-delivery"))
			.andExpect(flash().attribute("flashError", "The kitchen hasn't marked " + order.getCode()
					+ " ready yet. Pick it up once it's packed."));
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PREPARING);

		// the poll: nothing while unchanged, a page refresh once the kitchen marks it ready
		String poll = "/partner?delivery=" + order.getCode() + ":" + order.getVersion();
		mvc.perform(get(poll).header("HX-Request", "true").with(as(rider)))
			.andExpect(status().isNoContent())
			.andExpect(header().doesNotExist("HX-Refresh"));
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findById(order.getId()).orElseThrow(),
				OrderStatus.READY_FOR_PICKUP, OrderActor.restaurant(owner)));
		mvc.perform(get(poll).header("HX-Request", "true").with(as(rider)))
			.andExpect(status().isNoContent())
			.andExpect(header().string("HX-Refresh", "true"));

		String ready = page(rider);
		assertThat(ready).contains("action=\"/partner/orders/" + order.getCode() + "/pickup\"")
			.contains(">Picked up</button>")
			.doesNotContain("hx-get=\"/partner?delivery=");

		pickUp(rider, order).andExpect(redirectedUrl("/partner#current-delivery"))
			.andExpect(flash().attribute("flashSuccess",
					order.getCode() + " picked up. Ask the customer for their 4-digit delivery code at the door."));
		Order picked = reload(order);
		assertThat(picked.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
		assertThat(picked.timestampOf(OrderStatus.OUT_FOR_DELIVERY)).isEqualTo(MutableClock.DEFAULT_NOW);
		assertThat(picked.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

		// out for delivery: the code field and Delivered
		String out = page(rider);
		assertThat(out).contains("action=\"/partner/orders/" + order.getCode() + "/deliver\"")
			.contains("<label class=\"field-label\" for=\"otp\">Customer's delivery code</label>")
			.contains("inputmode=\"numeric\"")
			.contains("autocomplete=\"one-time-code\"")
			.contains(">Delivered</button>")
			.doesNotContain(">Picked up</button>")
			.doesNotContain("value=\"" + picked.getDeliveryOtp() + "\""); // the rider is never handed the code

		pickUp(rider, order).andExpect(flash().attribute("flashError", order.getCode() + " is already picked up."));
	}

	@Test
	void theCookingCardPollRefreshesTheRiderOnceTheDeliveryIsGone() throws Exception {
		Kitchen k = kitchen("Gone Kitchen");
		User rider = rider();
		Order order = claimed(k, rider, PaymentMethod.COD, OrderStatus.ACCEPTED);
		String poll = "/partner?delivery=" + order.getCode() + ":" + order.getVersion();
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findById(order.getId()).orElseThrow(),
				OrderStatus.CANCELLED, OrderActor.admin(1), "Kitchen ran out of gas"));
		mvc.perform(get(poll).header("HX-Request", "true").with(as(rider)))
			.andExpect(status().isNoContent())
			.andExpect(header().string("HX-Refresh", "true"));
		pickUp(rider, order).andExpect(flash().attribute("flashError", order.getCode()
				+ " was cancelled, so there's nothing to pick up."));
	}

	// ---------------------------------------------------------------- delivered

	@Test
	void theRightCodeDeliversACashOrderMarksItPaidAndRecordsTheEarning() throws Exception {
		Kitchen k = kitchen("Cash Kitchen");
		User rider = rider();
		Order order = onTheWay(k, rider, PaymentMethod.COD);
		BigDecimal total = order.getBill().grandTotal();
		String otp = order.getDeliveryOtp();

		clock.advance(Duration.ofMinutes(12));
		deliver(rider, order, " " + otp.substring(0, 2) + " " + otp.substring(2) + " ") // spaces are ignored
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashSuccess", order.getCode() + " delivered. You earned ₹54 and collected "
					+ fmt.price(total) + " in cash."));

		Order done = reload(order);
		assertThat(done.getStatus()).isEqualTo(OrderStatus.DELIVERED);
		assertThat(done.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(done.timestampOf(OrderStatus.DELIVERED)).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(12));

		RiderEarning earning = tx.execute(s -> {
			RiderEarning e = earnings.findByOrderId(order.getId()).orElseThrow();
			e.getRider().getEmail();
			return e;
		});
		assertThat(earning.getRider().getId()).isEqualTo(rider.getId());
		assertThat(earning.getBasePayout()).isEqualByComparingTo("30.00");
		assertThat(earning.getFeeShare()).isEqualByComparingTo("24.00");
		assertThat(earning.getAmount()).isEqualByComparingTo("54.00").isEqualByComparingTo(deliveries.payout(done));
		assertThat(earning.getAmount().scale()).isEqualTo(2);
		assertThat(earning.getCashCollected()).isEqualByComparingTo(total);
		assertThat(earning.getEarnedAt()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(12));

		// the rider is free again: no current delivery, back to the waiting list
		assertThat(page(rider)).doesNotContain("id=\"current-delivery\"").contains("Waiting for a rider");

		// a second Delivered (double tap, stale tab) changes nothing
		deliver(rider, order, otp).andExpect(redirectedUrl("/partner#current-delivery"))
			.andExpect(flash().attribute("flashError", order.getCode() + " is already delivered."));
		Long stillOne = tx.execute(s -> earnings.findByOrderId(order.getId()).orElseThrow().getId());
		assertThat(stillOne).isEqualTo(earning.getId());
	}

	@Test
	void anOrderPaidOnlineCollectsNoCash() throws Exception {
		Kitchen k = kitchen("Online Kitchen");
		User rider = rider();
		Order order = onTheWay(k, rider, PaymentMethod.ONLINE);
		jdbc.update("update orders set payment_status = 'PAID' where id = ?", order.getId()); // the fixture skips paying
		order = reload(order);

		deliver(rider, order, order.getDeliveryOtp())
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashSuccess", order.getCode() + " delivered. You earned ₹54."));
		Order done = reload(order);
		assertThat(done.getStatus()).isEqualTo(OrderStatus.DELIVERED);
		assertThat(done.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		RiderEarning earning = earnings.findByOrderId(order.getId()).orElseThrow();
		assertThat(earning.getAmount()).isEqualByComparingTo("54.00");
		assertThat(earning.getCashCollected()).isEqualByComparingTo("0.00");
	}

	@Test
	void aWrongCodeIsRefusedOnTheCodeFieldAndCounted() throws Exception {
		Kitchen k = kitchen("Wrong Code Kitchen");
		User rider = rider();
		Order order = onTheWay(k, rider, PaymentMethod.COD);
		String wrong = wrongCode(order);

		String html = squash(deliver(rider, order, wrong).andExpect(status().isOk())
			.andExpect(view().name("partner/dashboard")));
		assertThat(html).contains("id=\"otp-error\"")
			.contains("That code doesn&#39;t match. 4 tries left.")
			.contains("aria-invalid=\"true\"")
			.contains("aria-describedby=\"otp-error otp-help\"")
			.contains("value=\"" + wrong + "\"")
			.contains("<title>Rider dashboard | Zaika</title>");
		Order after = reload(order);
		assertThat(after.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
		assertThat(after.otpAttemptsLeft()).isEqualTo(4); // committed although the request failed
		assertThat(earnings.findByOrderId(order.getId())).isEmpty();

		// badly formed codes are refused without counting; user input is escaped
		for (String bad : List.of("", "12a4", "123", "12345", "<b>1</b>")) {
			String page = squash(deliver(rider, order, bad).andExpect(status().isOk()));
			assertThat(page).contains("Enter the 4-digit code from the customer&#39;s tracking page.")
				.doesNotContain("<b>1</b>");
		}
		mvc.perform(post("/partner/orders/" + order.getCode() + "/deliver").with(csrf()).with(as(rider)))
			.andExpect(status().isOk()); // no code at all
		assertThat(reload(order).otpAttemptsLeft()).isEqualTo(4);

		// three more wrong: one try left, said in the singular
		deliver(rider, order, wrong);
		deliver(rider, order, wrong);
		assertThat(squash(deliver(rider, order, wrong))).contains("That code doesn&#39;t match. 1 try left.");

		// the right code still works and clears the count
		deliver(rider, order, order.getDeliveryOtp()).andExpect(redirectedUrl("/partner"));
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.DELIVERED);
	}

	@Test
	void fiveWrongCodesLockTheCodeForTenMinutes() throws Exception {
		Kitchen k = kitchen("Lock Kitchen");
		User rider = rider();
		Order order = onTheWay(k, rider, PaymentMethod.COD);
		String wrong = wrongCode(order);

		for (int i = 0; i < 4; i++) {
			deliver(rider, order, wrong).andExpect(status().isOk());
		}
		assertThat(squash(deliver(rider, order, wrong)))
			.contains("That&#39;s 5 wrong codes, so the code is locked until 7:40 PM. Call the customer to check the code.");
		assertThat(reload(order).getOtpLockedUntil()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(10));

		// even the right code waits for the lock
		clock.advance(Duration.ofMinutes(9));
		assertThat(squash(deliver(rider, order, order.getDeliveryOtp())))
			.contains("Too many wrong codes. You can try again at 7:40 PM. Call the customer to check the code.");
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
		assertThat(earnings.findByOrderId(order.getId())).isEmpty();

		// after ten minutes a fresh count starts
		clock.advance(Duration.ofMinutes(1));
		assertThat(squash(deliver(rider, order, wrong))).contains("That code doesn&#39;t match. 4 tries left.");
		deliver(rider, order, order.getDeliveryOtp()).andExpect(redirectedUrl("/partner"));
		Order done = reload(order);
		assertThat(done.getStatus()).isEqualTo(OrderStatus.DELIVERED);
		assertThat(done.getOtpLockedUntil()).isNull();
		assertThat(earnings.findByOrderId(order.getId())).isPresent();
	}

	@Test
	void deliveredNeedsTheOrderPickedUpFirst() throws Exception {
		Kitchen k = kitchen("Not Yet Kitchen");
		User rider = rider();
		Order order = claimed(k, rider, PaymentMethod.COD, OrderStatus.READY_FOR_PICKUP);

		deliver(rider, order, order.getDeliveryOtp()).andExpect(redirectedUrl("/partner#current-delivery"))
			.andExpect(flash().attribute("flashError", "Mark " + order.getCode() + " picked up before you deliver it."));
		deliver(rider, order, wrongCode(order)).andExpect(redirectedUrl("/partner#current-delivery"));
		Order after = reload(order);
		assertThat(after.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
		assertThat(after.otpAttemptsLeft()).isEqualTo(5); // no code is checked before the pickup
		assertThat(earnings.findByOrderId(order.getId())).isEmpty();
	}

	@Test
	void anOrderCancelledOnTheWayCannotBeDelivered() throws Exception {
		Kitchen k = kitchen("Cancelled Kitchen");
		User rider = rider();
		Order order = onTheWay(k, rider, PaymentMethod.COD);
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findById(order.getId()).orElseThrow(),
				OrderStatus.CANCELLED, OrderActor.admin(1), "Customer unreachable"));

		deliver(rider, order, order.getDeliveryOtp()).andExpect(redirectedUrl("/partner#current-delivery"))
			.andExpect(flash().attribute("flashError", order.getCode()
					+ " was cancelled, so there's nothing to deliver. Take the food back to Cancelled Kitchen."));
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(reload(order).getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(earnings.findByOrderId(order.getId())).isEmpty();
	}

	// ---------------------------------------------------------------- whose delivery

	@Test
	void anotherRidersDeliveryIsNotFound() throws Exception {
		Kitchen k = kitchen("Other Rider Kitchen");
		User mine = rider();
		User other = rider();
		Order ready = claimed(k, mine, PaymentMethod.COD, OrderStatus.READY_FOR_PICKUP);

		pickUp(other, ready).andExpect(status().isNotFound());
		deliver(other, ready, ready.getDeliveryOtp()).andExpect(status().isNotFound());
		assertThat(reload(ready).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);

		deliveries.pickUp(mine.getId(), ready.getCode());
		deliver(other, ready, ready.getDeliveryOtp()).andExpect(status().isNotFound());
		deliver(other, ready, wrongCode(ready)).andExpect(status().isNotFound());
		Order after = reload(ready);
		assertThat(after.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
		assertThat(after.otpAttemptsLeft()).isEqualTo(5);

		// an unclaimed order and an unknown code are not found either
		Order unclaimed = orders.place(customer().getId(), k.thali().getId(), 1, PaymentMethod.COD,
				OrderStatus.READY_FOR_PICKUP);
		pickUp(other, unclaimed).andExpect(status().isNotFound());
		mvc.perform(post("/partner/orders/ZK-26-999999/pickup").with(csrf()).with(as(other)))
			.andExpect(status().isNotFound());
		assertThat(reload(unclaimed).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
	}

	@Test
	void onlyRidersPickUpAndDeliver() throws Exception {
		Kitchen k = kitchen("Guarded Flow Kitchen");
		User rider = rider();
		Order order = claimed(k, rider, PaymentMethod.COD, OrderStatus.READY_FOR_PICKUP);
		String pickUp = "/partner/orders/" + order.getCode() + "/pickup";
		String deliver = "/partner/orders/" + order.getCode() + "/deliver";

		mvc.perform(post(pickUp).with(csrf())).andExpect(redirectedUrl("/login"));
		mvc.perform(post(deliver).param("otp", "1234").with(csrf())).andExpect(redirectedUrl("/login"));
		for (User notARider : List.of(customer(), k.restaurant().getOwner())) {
			mvc.perform(post(pickUp).with(csrf()).with(as(notARider))).andExpect(status().isForbidden());
			mvc.perform(post(deliver).param("otp", "1234").with(csrf()).with(as(notARider)))
				.andExpect(status().isForbidden());
		}
		mvc.perform(post(pickUp).with(as(rider))).andExpect(status().isForbidden()); // no CSRF token
		mvc.perform(post(deliver).param("otp", order.getDeliveryOtp()).with(as(rider)))
			.andExpect(status().isForbidden());
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
	}

}
