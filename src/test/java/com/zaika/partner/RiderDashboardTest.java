package com.zaika.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.common.Formatters;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.DeliveryRuleException;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T35: the rider dashboard ({@code /partner}): the online switch (approved riders only), the orders waiting for a
 * rider (accepted, preparing or ready, nobody assigned) with the restaurant's area and the payout, claiming one (at
 * most one live delivery; when two riders claim at once one wins and the other is told so), and the current delivery
 * card (restaurant address and phone, customer address and masked phone, item count, cash to collect). The clock
 * stands at Sunday 4 Oct 2026, 7:30 PM.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:riderdash;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RiderDashboardTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@MockitoSpyBean
	OrderRepository orderRepository;

	@Autowired
	OrderWorkflow workflow;

	@Autowired
	DeliveryService deliveries;

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
	EntityManagerFactory entityManagerFactory;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Formatters fmt;

	/** Orders other tests left waiting go to a rider of their own, so each test sees only its own waiting orders. */
	@BeforeEach
	void clearWaitingOrders() {
		jdbc.update("update orders set rider_id = ? where rider_id is null", orders.rider().getId());
	}

	/** A kitchen in Rajarampuri (₹30 delivery fee, free above ₹499) with a ₹250 thali. */
	record Kitchen(Restaurant restaurant, MenuItem thali) {
	}

	Kitchen kitchen(String name) {
		String slug = "rider-" + UUID.randomUUID().toString().substring(0, 8);
		Restaurant r = fixtures.restaurant(slug, name).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		return new Kitchen(r, items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false)));
	}

	/** A customer whose number differs from the fixture restaurants' (9876543210), to check the masking. */
	User customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@riders.zaika.test", "9123456789",
				"hash", Role.CUSTOMER));
	}

	/** One thali (₹250 + ₹30 delivery: the rider earns ₹30 + 80% of ₹30 = ₹54), cash on delivery. */
	Order place(Kitchen k, OrderStatus status) {
		return place(k, 1, PaymentMethod.COD, status);
	}

	Order place(Kitchen k, int thalis, PaymentMethod method, OrderStatus status) {
		return orders.place(customer().getId(), k.thali().getId(), thalis, method, status);
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

	String page(User rider) throws Exception {
		return mvc.perform(get("/partner").with(as(rider)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	Order reload(Order order) {
		return tx.execute(s -> {
			Order o = orderRepository.findById(order.getId()).orElseThrow();
			if (o.getRider() != null) {
				o.getRider().getEmail();
			}
			return o;
		});
	}

	Long riderIdOf(Order order) {
		User rider = reload(order).getRider();
		return rider == null ? null : rider.getId();
	}

	// ---------------------------------------------------------------- online switch and waiting orders

	@Test
	void anApprovedRiderGoesOnlineAndSeesOrdersWaitingForARider() throws Exception {
		Kitchen k = kitchen("Tambda Rassa House");
		Order accepted = place(k, OrderStatus.ACCEPTED);
		Order preparing = place(k, OrderStatus.PREPARING);
		Order ready = place(k, OrderStatus.READY_FOR_PICKUP);
		Order freeDelivery = place(k, 2, PaymentMethod.ONLINE, OrderStatus.READY_FOR_PICKUP);
		Order placed = place(k, OrderStatus.PLACED);
		Order unpaid = place(k, 1, PaymentMethod.ONLINE, OrderStatus.PENDING_PAYMENT);
		Order onTheWay = place(k, OrderStatus.OUT_FOR_DELIVERY);
		Order delivered = place(k, OrderStatus.DELIVERED);
		Order cancelled = orders.placeThenCancel(customer().getId(), k.thali().getId(), 1, PaymentMethod.COD);
		User rider = rider(RiderStatus.APPROVED, false);

		String offline = page(rider);
		assertThat(offline).contains("role=\"switch\" aria-checked=\"false\"", "<strong>You're offline.</strong>",
				"name=\"online\" value=\"true\"", "action=\"/partner/online\"")
			.doesNotContain("id=\"partner-available\"", accepted.getCode());

		mvc.perform(post("/partner/online").param("online", "true").with(csrf()).with(as(rider)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashSuccess", "You're online. Orders waiting for a rider show up below."));
		assertThat(profiles.findByUserId(rider.getId()).orElseThrow().isOnline()).isTrue();

		String online = page(rider);
		assertThat(online).contains("role=\"switch\" aria-checked=\"true\"", "<strong>You're online.</strong>",
				"name=\"online\" value=\"false\"", "id=\"partner-available\"",
				"hx-get=\"/partner?v=" + deliveries.dashboard(rider.getId()).orElseThrow().version()
						+ "\" hx-trigger=\"every 15s\" hx-swap=\"outerHTML\"",
				"id=\"claim-" + ready.getCode() + "\"", "id=\"partner-refresh\"",
				"Waiting for a rider <span class=\"board-count\">4</span>", "Tambda Rassa House", "Rajarampuri",
				"Rajarampuri 5th Lane", "Ready now", "Ready by 7:50 PM", "Cash order",
				"<span class=\"visually-hidden\">You earn </span>₹54", "<span class=\"visually-hidden\">You earn </span>₹30",
				"action=\"/partner/orders/" + ready.getCode() + "/claim\"");
		for (Order waiting : List.of(accepted, preparing, ready, freeDelivery)) {
			assertThat(online).contains("id=\"offer-" + waiting.getCode() + "\"");
		}
		for (Order notWaiting : List.of(placed, unpaid, onTheWay, delivered, cancelled)) {
			assertThat(online).doesNotContain(notWaiting.getCode());
		}
		// oldest first
		assertThat(online.indexOf(accepted.getCode())).isLessThan(online.indexOf(preparing.getCode()));
		assertThat(online.indexOf(preparing.getCode())).isLessThan(online.indexOf(ready.getCode()));

		mvc.perform(post("/partner/online").param("online", "false").with(csrf()).with(as(rider)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashInfo",
					"You're offline. You won't see new orders until you go online again."));
		assertThat(profiles.findByUserId(rider.getId()).orElseThrow().isOnline()).isFalse();
		assertThat(page(rider)).doesNotContain("id=\"partner-available\"");
	}

	@Test
	void theWaitingListPollAnswers204UntilItChangesThenOnlyTheFragment() throws Exception {
		Kitchen k = kitchen("Poll Kitchen");
		Order first = place(k, OrderStatus.READY_FOR_PICKUP);
		User rider = rider(RiderStatus.APPROVED, true);
		String v = deliveries.dashboard(rider.getId()).orElseThrow().version();

		mvc.perform(poll(rider, v))
			.andExpect(status().isNoContent())
			.andExpect(header().string("Vary", "HX-Request"))
			.andExpect(header().doesNotExist("HX-Refresh"))
			.andExpect(content().string(""));

		Order second = place(k, OrderStatus.ACCEPTED);
		String fragment = mvc.perform(poll(rider, v))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		String now = deliveries.dashboard(rider.getId()).orElseThrow().version();
		assertThat(now).isNotEqualTo(v);
		assertThat(fragment.strip()).startsWith("<section id=\"partner-available\"");
		assertThat(fragment).contains("hx-get=\"/partner?v=" + now + "\"", "id=\"claim-" + first.getCode() + "\"",
				"id=\"claim-" + second.getCode() + "\"", "<span class=\"board-count\">2</span>")
			.doesNotContain("<html", "<header", "Your details", "role=\"switch\"");
		mvc.perform(poll(rider, now)).andExpect(status().isNoContent());

		// a kitchen update to a waiting order (ready time, status) also counts as a change
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findById(second.getId()).orElseThrow(),
				OrderStatus.PREPARING, OrderActor.restaurant(k.restaurant().getOwner().getId())));
		mvc.perform(poll(rider, now)).andExpect(status().isOk());

		// a history restore gets the whole page
		assertThat(mvc.perform(poll(rider, now).header("HX-History-Restore-Request", "true"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).contains("<html", "Your details");
	}

	@Test
	void theWaitingListPollRefreshesThePageWhenTheListNoLongerBelongsOnIt() throws Exception {
		Kitchen k = kitchen("Refresh Kitchen");
		Order order = place(k, OrderStatus.READY_FOR_PICKUP);
		User rider = rider(RiderStatus.APPROVED, true);
		String v = deliveries.dashboard(rider.getId()).orElseThrow().version();

		// claimed in another tab: the page must show the current delivery instead
		deliveries.claim(rider.getId(), order.getCode());
		expectRefresh(rider, v);

		// gone offline in another tab
		User offline = rider(RiderStatus.APPROVED, true);
		String offlineV = deliveries.dashboard(offline.getId()).orElseThrow().version();
		deliveries.setOnline(offline.getId(), false);
		expectRefresh(offline, offlineV);

		// suspended by an admin while online
		User suspended = rider(RiderStatus.APPROVED, true);
		tx.executeWithoutResult(s -> profiles.findByUserId(suspended.getId()).orElseThrow()
			.changeStatus(RiderStatus.SUSPENDED, "Three late deliveries"));
		expectRefresh(suspended, offlineV);

		// a rider account without a profile
		expectRefresh(orders.rider(), offlineV);
	}

	private MockHttpServletRequestBuilder poll(User rider, String v) {
		return get("/partner").param("v", v).header("HX-Request", "true").with(as(rider));
	}

	private void expectRefresh(User rider, String v) throws Exception {
		mvc.perform(poll(rider, v))
			.andExpect(status().isNoContent())
			.andExpect(header().string("HX-Refresh", "true"))
			.andExpect(content().string(""));
	}

	@Test
	void anOnlineRiderWithNothingWaitingSeesAnEmptyState() throws Exception {
		assertThat(page(rider(RiderStatus.APPROVED, true))).contains("id=\"partner-available\"",
				"No orders waiting right now", "This list refreshes on its own.");
	}

	@Test
	void pendingAndSuspendedRidersCannotGoOnlineOrClaim() throws Exception {
		Order ready = place(kitchen("Waiting Kitchen"), OrderStatus.READY_FOR_PICKUP);
		User pending = rider(RiderStatus.PENDING, false);
		User suspended = rider(RiderStatus.SUSPENDED, false);

		assertThat(page(pending)).doesNotContain("role=\"switch\"", "id=\"partner-available\"", ready.getCode());
		assertThat(page(suspended)).doesNotContain("role=\"switch\"", "id=\"partner-available\"", ready.getCode());

		mvc.perform(post("/partner/online").param("online", "true").with(csrf()).with(as(pending)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError", "You can go online once your account is approved."));
		mvc.perform(post("/partner/online").param("online", "true").with(csrf()).with(as(suspended)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError", "Your account is suspended, so you can't go online."));
		assertThat(profiles.findByUserId(pending.getId()).orElseThrow().isOnline()).isFalse();
		assertThat(profiles.findByUserId(suspended.getId()).orElseThrow().isOnline()).isFalse();

		mvc.perform(post("/partner/orders/" + ready.getCode() + "/claim").with(csrf()).with(as(suspended)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError", "Your account is suspended, so you can't take orders."));
		mvc.perform(post("/partner/orders/" + ready.getCode() + "/claim").with(csrf()).with(as(pending)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError", "You can take orders once your account is approved."));
		assertThat(riderIdOf(ready)).isNull();
	}

	// ---------------------------------------------------------------- claiming and the current delivery

	@Test
	void claimingGivesTheRiderTheOrderAndShowsTheCurrentDelivery() throws Exception {
		Kitchen k = kitchen("Tambda Rassa House");
		Order cash = place(k, 3, PaymentMethod.COD, OrderStatus.READY_FOR_PICKUP); // ₹750: free delivery
		long versionBefore = reload(cash).getVersion();
		User rider = rider(RiderStatus.APPROVED, true);

		mvc.perform(post("/partner/orders/" + cash.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(redirectedUrl("/partner#current-delivery"))
			.andExpect(flash().attribute("flashSuccess",
					cash.getCode() + " is yours. Head to Tambda Rassa House for the pickup."));
		Order claimed = reload(cash);
		assertThat(claimed.getRider().getId()).isEqualTo(rider.getId());
		assertThat(claimed.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP); // not a status change
		assertThat(claimed.getVersion()).isEqualTo(versionBefore + 1); // tracking pages notice the rider

		String page = page(rider);
		assertThat(page).contains("id=\"current-delivery\"", "Current delivery", cash.getCode() + "</span> · 3 items",
				"Ready for pickup", "<strong>Packed and waiting for you.</strong>",
				"<p class=\"rider-stop-name\">Tambda Rassa House</p>", "12, Main Road, Rajarampuri, Kolhapur 416008",
				"href=\"tel:+919876543210\"", "+91 98765 43210",
				"<p class=\"rider-stop-name\">Riya Shinde</p>", OrderFixtures.ADDRESS.oneLine(), "+91 91••• ••789",
				"Cash to collect", fmt.price(claimed.getBill().grandTotal()), "You earn", "₹30")
			.doesNotContain("9123456789", "91234 56789", "id=\"partner-available\"");

		// an online order has nothing to collect, and pays the base plus the rider's share of the fee
		User second = rider(RiderStatus.APPROVED, true);
		Order paid = place(k, 1, PaymentMethod.ONLINE, OrderStatus.PREPARING);
		deliveries.claim(second.getId(), paid.getCode());
		assertThat(page(second)).contains("Paid online. Nothing to collect.", "₹54",
				"<strong>The kitchen is on it.</strong> Food should be packed by 7:50 PM.", "1 item")
			.doesNotContain("Cash to collect");
	}

	@Test
	void aRiderHasAtMostOneLiveDelivery() throws Exception {
		Kitchen k = kitchen("Busy Kitchen");
		Order first = place(k, OrderStatus.READY_FOR_PICKUP);
		Order second = place(k, OrderStatus.READY_FOR_PICKUP);
		User rider = rider(RiderStatus.APPROVED, true);

		mvc.perform(post("/partner/orders/" + first.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(flash().attributeExists("flashSuccess"));
		mvc.perform(post("/partner/orders/" + second.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError", "Finish your current delivery before you take another order."));
		mvc.perform(post("/partner/orders/" + first.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(flash().attribute("flashError", first.getCode() + " is already yours."));
		assertThat(riderIdOf(second)).isNull();

		// still out with it: going offline keeps the delivery on the dashboard
		tx.executeWithoutResult(s -> {
			Order o = orderRepository.findById(first.getId()).orElseThrow();
			workflow.transition(o, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(rider.getId()));
		});
		mvc.perform(post("/partner/online").param("online", "false").with(csrf()).with(as(rider)));
		assertThat(page(rider)).contains("id=\"current-delivery\"", first.getCode(), "<strong>On the way.</strong>");
		assertThatClaimFails(rider, second, "Go online to take orders.");

		// once it is delivered the rider is free again
		tx.executeWithoutResult(s -> {
			Order o = orderRepository.findById(first.getId()).orElseThrow();
			workflow.transition(o, OrderStatus.DELIVERED, OrderActor.rider(rider.getId()));
		});
		mvc.perform(post("/partner/online").param("online", "true").with(csrf()).with(as(rider)));
		assertThat(page(rider)).doesNotContain("id=\"current-delivery\"").contains("id=\"offer-" + second.getCode() + "\"");
		assertThat(deliveries.claim(rider.getId(), second.getCode()).getCode()).isEqualTo(second.getCode());
	}

	@Test
	void ordersThatAreNotWaitingForARiderCannotBeClaimed() throws Exception {
		Kitchen k = kitchen("Strict Kitchen");
		User rider = rider(RiderStatus.APPROVED, true);
		User other = rider(RiderStatus.APPROVED, true);
		Order theirs = place(k, OrderStatus.READY_FOR_PICKUP);
		deliveries.claim(other.getId(), theirs.getCode());

		assertThatClaimFails(rider, theirs, "Another partner just took " + theirs.getCode() + ". Pick another order.");
		assertThat(riderIdOf(theirs)).isEqualTo(other.getId());
		Order placed = place(k, OrderStatus.PLACED);
		assertThatClaimFails(rider, placed, "The kitchen hasn't accepted " + placed.getCode() + " yet.");
		Order cancelled = orders.placeThenCancel(customer().getId(), k.thali().getId(), 1, PaymentMethod.COD);
		assertThatClaimFails(rider, cancelled, cancelled.getCode() + " was cancelled, so there's nothing to pick up.");

		// never reached a kitchen, or no such order: 404
		Order unpaid = place(k, 1, PaymentMethod.ONLINE, OrderStatus.PENDING_PAYMENT);
		mvc.perform(post("/partner/orders/" + unpaid.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(status().isNotFound());
		mvc.perform(post("/partner/orders/ZK-26-999999/claim").with(csrf()).with(as(rider)))
			.andExpect(status().isNotFound());
		assertThat(riderIdOf(placed)).isNull();
		assertThat(riderIdOf(unpaid)).isNull();
	}

	@Test
	void eachRiderSeesOnlyTheirOwnDelivery() throws Exception {
		Kitchen k = kitchen("Shared Kitchen");
		User mine = rider(RiderStatus.APPROVED, true);
		User other = rider(RiderStatus.APPROVED, true);
		Order order = place(k, OrderStatus.READY_FOR_PICKUP);
		deliveries.claim(mine.getId(), order.getCode());

		assertThat(page(mine)).contains("id=\"current-delivery\"", order.getCode());
		assertThat(page(other)).doesNotContain("id=\"current-delivery\"", order.getCode());
	}

	// ---------------------------------------------------------------- two riders at once

	@Test
	void whenTwoRidersClaimAtOnceTheSecondIsToldSomeoneElseWasFaster() throws Exception {
		Order order = place(kitchen("Race Kitchen"), OrderStatus.READY_FOR_PICKUP);
		User slow = rider(RiderStatus.APPROVED, true);
		User fast = rider(RiderStatus.APPROVED, true);
		// the slow rider reads the order (nobody assigned yet), then the fast rider's claim commits before the slow
		// rider writes: the slow rider's write hits a newer version
		interleave(() -> deliveries.claim(fast.getId(), order.getCode()));

		mvc.perform(post("/partner/orders/" + order.getCode() + "/claim").with(csrf()).with(as(slow)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError",
					"Another partner just took " + order.getCode() + ". Pick another order."));
		assertThat(riderIdOf(order)).isEqualTo(fast.getId());
		// slow rider's read, the fast rider's, then the slow rider's second look after the failed write
		verify(orderRepository, times(3)).findByCode(order.getCode());
		assertThat(page(slow)).doesNotContain("id=\"current-delivery\"");
	}

	@Test
	void aKitchenUpdateDuringAClaimDoesNotCostTheRiderTheOrder() throws Exception {
		Kitchen k = kitchen("Busy Stove");
		Order order = place(k, OrderStatus.PREPARING);
		User rider = rider(RiderStatus.APPROVED, true);
		long ownerId = k.restaurant().getOwner().getId();
		interleave(() -> tx.executeWithoutResult(s -> workflow.transition(
				orderRepository.findById(order.getId()).orElseThrow(), OrderStatus.READY_FOR_PICKUP,
				OrderActor.restaurant(ownerId))));

		mvc.perform(post("/partner/orders/" + order.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(redirectedUrl("/partner#current-delivery"));
		Order claimed = reload(order);
		assertThat(claimed.getRider().getId()).isEqualTo(rider.getId());
		assertThat(claimed.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
	}

	@Test
	void ridersClaimingInParallelLeaveExactlyOneWinner() throws Exception {
		Kitchen k = kitchen("Rush Hour Kitchen");
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			for (int round = 0; round < 4; round++) {
				Order order = place(k, OrderStatus.READY_FOR_PICKUP);
				List<User> riders = List.of(rider(RiderStatus.APPROVED, true), rider(RiderStatus.APPROVED, true),
						rider(RiderStatus.APPROVED, true));
				CyclicBarrier start = new CyclicBarrier(riders.size());
				List<Future<Long>> claims = new ArrayList<>();
				for (User r : riders) {
					claims.add(pool.submit(() -> {
						start.await();
						try {
							deliveries.claim(r.getId(), order.getCode());
							return r.getId();
						}
						catch (DeliveryRuleException ex) {
							assertThat(ex.getMessage()).isEqualTo(
									"Another partner just took " + order.getCode() + ". Pick another order.");
							return null;
						}
					}));
				}
				List<Long> winners = new ArrayList<>();
				for (Future<Long> claim : claims) {
					Long winner = claim.get();
					if (winner != null) {
						winners.add(winner);
					}
				}
				assertThat(winners).hasSize(1);
				assertThat(riderIdOf(order)).isEqualTo(winners.get(0));
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Runs {@code meanwhile} on another thread (and waits for it) right after the first {@code findByCode} has read
	 * the order, before the caller writes it.
	 */
	private void interleave(Runnable meanwhile) {
		AtomicReference<Runnable> once = new AtomicReference<>(meanwhile);
		// the spy wraps a JDK proxy: its default answer delegates to the real repository
		Answer<?> real = mockingDetails(orderRepository).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			Object read = real.answer(invocation);
			Runnable action = once.getAndSet(null);
			if (action != null) {
				Thread other = new Thread(action);
				other.start();
				other.join(10_000);
			}
			return read;
		}).when(orderRepository).findByCode(anyString());
	}

	// ---------------------------------------------------------------- queries and access

	@Test
	void theDashboardRunsAFixedNumberOfQueries() throws Exception {
		Kitchen k = kitchen("Query Kitchen");
		User rider = rider(RiderStatus.APPROVED, true);
		place(k, OrderStatus.READY_FOR_PICKUP);
		page(rider); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		page(rider);
		long one = stats.getPrepareStatementCount();
		for (int i = 0; i < 6; i++) {
			place(k, i % 2 == 0 ? OrderStatus.ACCEPTED : OrderStatus.READY_FOR_PICKUP);
		}
		stats.clear();
		assertThat(page(rider)).contains("Waiting for a rider <span class=\"board-count\">7</span>");
		long seven = stats.getPrepareStatementCount();
		// profile with account, live delivery, waiting orders with restaurants, their dishes, cart count: none per order
		assertThat(seven).isEqualTo(one);
		assertThat(one).isLessThanOrEqualTo(5);
	}

	@Test
	void onlyRidersUseTheRiderPanel() throws Exception {
		Kitchen k = kitchen("Guarded Kitchen");
		Order order = place(k, OrderStatus.READY_FOR_PICKUP);
		String claim = "/partner/orders/" + order.getCode() + "/claim";

		mvc.perform(post("/partner/online").param("online", "true").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		mvc.perform(post(claim).with(csrf())).andExpect(redirectedUrl("/login"));
		for (User notARider : List.of(customer(), k.restaurant().getOwner())) {
			mvc.perform(post("/partner/online").param("online", "true").with(csrf()).with(as(notARider)))
				.andExpect(status().isForbidden());
			mvc.perform(post(claim).with(csrf()).with(as(notARider))).andExpect(status().isForbidden());
		}
		User rider = rider(RiderStatus.APPROVED, true);
		mvc.perform(post(claim).with(as(rider))).andExpect(status().isForbidden()); // no CSRF token
		mvc.perform(post("/partner/online").param("online", "maybe").with(csrf()).with(as(rider)))
			.andExpect(status().isBadRequest());
		assertThat(riderIdOf(order)).isNull();

		// a rider account without a profile has nothing to switch or claim
		User lone = orders.rider();
		mvc.perform(post("/partner/online").param("online", "true").with(csrf()).with(as(lone)))
			.andExpect(status().isNotFound());
		mvc.perform(post(claim).with(csrf()).with(as(lone))).andExpect(status().isNotFound());
		assertThat(riderIdOf(order)).isNull();
	}

	private void assertThatClaimFails(User rider, Order order, String message) throws Exception {
		mvc.perform(post("/partner/orders/" + order.getCode() + "/claim").with(csrf()).with(as(rider)))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashError", message));
	}

}
