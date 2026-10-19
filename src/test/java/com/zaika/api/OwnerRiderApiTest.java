package com.zaika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * The owner API ({@code /api/v1/owner/**}) and the rider API ({@code /api/v1/rider/**}). Shares OrderPagesTest's
 * context and database (Spring's context cache is full), so its restaurants are all "Numbat ..." and every test makes
 * its own accounts. Rider tests place their orders a year back, so they head the platform-wide waiting list whatever
 * other classes left in the shared database. Clock: Sunday 4 Oct 2026, 19:30 IST. Dishes cost ₹199; delivery ₹30,
 * platform fee ₹5, GST 5%, so a cash order of one dish is ₹243.95 and pays its rider ₹30 + 80% of ₹30 = ₹54.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerRiderApiTest {

	static final String PASSWORD = "Numbat@2026";

	static final String PROBLEM = "application/problem+json";

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	MenuItemRepository items;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	UserRepository users;

	@Autowired
	OrderRepository orderRows;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	MenuItem misal;

	@BeforeAll
	void createRestaurant() {
		misal = dish(fixtures.restaurant("numbat-rider-kitchen", "Numbat Rider Kitchen").cuisines("Kolhapuri")
			.dishes("Numbat Misal").save(), "Numbat Misal");
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	MenuItem dish(Restaurant restaurant, String name) {
		return items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId()).stream()
			.filter(item -> item.getName().equals(name))
			.findFirst()
			.orElseThrow();
	}

	// ---------------------------------------------------------------- helpers

	record Account(long id, String token) {
	}

	/** Signs the account in through the API, after giving it a known password. */
	Account signIn(User user) throws Exception {
		jdbc.update("update users set password_hash = ? where id = ?", passwordEncoder.encode(PASSWORD), user.getId());
		MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"" + user.getEmail() + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return new Account(user.getId(), JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken"));
	}

	Account account(Role role) throws Exception {
		return signIn(users.save(new User("Sagar Patil", "api-" + UUID.randomUUID() + "@numbat.zaika.test",
				"9822012345", "hash", role)));
	}

	/** A restaurant of its own (one "Numbat ..." dish each), its owner signed in. */
	record Kitchen(Restaurant restaurant, MenuItem dish, Account owner) {
	}

	Kitchen kitchen(String name, RestaurantStatus status) throws Exception {
		String slug = "numbat-" + UUID.randomUUID().toString().substring(0, 8);
		Restaurant r = fixtures.restaurant(slug, name).cuisines("Kolhapuri").status(status)
			.dishes(name + " Thali", name + " Lassi").save();
		return new Kitchen(r, dish(r, name + " Thali"), signIn(users.findByEmailIgnoreCase(slug + "@fixtures.zaika.test")
			.orElseThrow()));
	}

	/** An approved rider with a profile, offline, signed in. */
	Account rider(RiderStatus status) throws Exception {
		Account rider = account(Role.DELIVERY_PARTNER);
		DeliveryPartnerProfile profile = new DeliveryPartnerProfile(users.findById(rider.id()).orElseThrow(),
				VehicleType.BIKE, "MH09EK4521", "Rajarampuri");
		profile.changeStatus(status, status == RiderStatus.SUSPENDED ? "Three late deliveries" : null);
		profiles.save(profile);
		return rider;
	}

	/** An order of one Numbat Misal at {@code target}, placed a year back so it heads the waiting list. */
	Order waiting(OrderStatus target) {
		clock.setTo(MutableClock.DEFAULT_NOW.minusYears(1));
		try {
			return orders.place(orders.customer().getId(), misal.getId(), 1, PaymentMethod.COD, target);
		}
		finally {
			clock.reset();
		}
	}

	Order order(Kitchen k, OrderStatus target) {
		return orders.place(orders.customer().getId(), k.dish().getId(), 1, PaymentMethod.COD, target);
	}

	ResultActions call(Account who, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.token()));
	}

	ResultActions send(Account who, MockHttpServletRequestBuilder request, String json) throws Exception {
		return call(who, request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	Order reload(Order order) {
		return orderRows.findById(order.getId()).orElseThrow();
	}

	static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	// ---------------------------------------------------------------- owner: orders

	@Test
	void theBoardListsTheKitchensLiveOrdersOldestFirst() throws Exception {
		Kitchen k = kitchen("Numbat Board", RestaurantStatus.APPROVED);
		Order fresh = order(k, OrderStatus.PLACED);
		Order cooking = order(k, OrderStatus.PREPARING);
		Order packed = order(k, OrderStatus.READY_FOR_PICKUP);
		order(k, OrderStatus.DELIVERED);
		orders.placeThenCancel(orders.customer().getId(), k.dish().getId(), 1, PaymentMethod.COD);
		orders.create(orders.customer().getId(), k.dish().getId(), 1, PaymentMethod.ONLINE); // awaiting payment

		ResultActions board = call(k.owner(), get("/api/v1/owner/orders")).andExpect(status().isOk())
			.andExpect(jsonPath("$.restaurant.slug").value(k.restaurant().getSlug()))
			.andExpect(jsonPath("$.restaurant.status").value("APPROVED"))
			.andExpect(jsonPath("$.accepting.acceptingOrders").value(true))
			.andExpect(jsonPath("$.accepting.openNow").value(true))
			.andExpect(jsonPath("$.accepting.opensAt").value("11:00"))
			.andExpect(jsonPath("$.orders[*].code").value(contains(fresh.getCode(), cooking.getCode(), packed.getCode())))
			.andExpect(jsonPath("$.orders[0].status").value("PLACED"))
			.andExpect(jsonPath("$.orders[0].customerName").value("Riya Shinde"))
			.andExpect(jsonPath("$.orders[0].items[0].name").value("Numbat Board Thali"))
			.andExpect(jsonPath("$.orders[0].items[0].veg").value(false))
			.andExpect(jsonPath("$.orders[0].itemCount").value(1))
			.andExpect(jsonPath("$.orders[0].itemTotal").value(199.0))
			.andExpect(jsonPath("$.orders[0].paymentMethod").value("COD"))
			.andExpect(jsonPath("$.orders[0].placedAt").value("2026-10-04T19:30:00+05:30"))
			.andExpect(jsonPath("$.orders[0].readyBy").value(nullValue()))
			.andExpect(jsonPath("$.orders[1].statusLabel").value("Preparing"))
			.andExpect(jsonPath("$.orders[1].readyBy").exists())
			.andExpect(jsonPath("$.orders[1].late").value(false))
			.andExpect(jsonPath("$.prepChoices").value(contains(10, 15, 20, 30, 45)))
			.andExpect(jsonPath("$.rejectReasons[0]").value("Kitchen is too busy right now"))
			.andExpect(jsonPath("$.version").isString());
		String json = body(board);
		assertThat(json).doesNotContain("9876543210").doesNotContain("Rajarampuri 5th Lane")
			.doesNotContain(reload(fresh).getDeliveryOtp());

		call(k.owner(), get("/api/v1/owner/orders/" + cooking.getCode())).andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(cooking.getCode()))
			.andExpect(jsonPath("$.status").value("PREPARING"));
		Order done = order(k, OrderStatus.DELIVERED);
		call(k.owner(), get("/api/v1/owner/orders/" + done.getCode())).andExpect(status().isOk())
			.andExpect(jsonPath("$.riderName").value("Sagar Patil"))
			.andExpect(jsonPath("$.paymentStatus").value("PAID"));

		Kitchen empty = kitchen("Numbat Quiet", RestaurantStatus.APPROVED);
		call(empty.owner(), get("/api/v1/owner/orders")).andExpect(status().isOk())
			.andExpect(jsonPath("$.orders", hasSize(0)));
		call(account(Role.RESTAURANT_OWNER), get("/api/v1/owner/orders")).andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM));
	}

	@Test
	void acceptPreparingAndReadyMoveTheOrderThroughTheKitchen() throws Exception {
		Kitchen k = kitchen("Numbat Stove", RestaurantStatus.APPROVED);
		Order order = order(k, OrderStatus.PLACED);
		String url = "/api/v1/owner/orders/" + order.getCode();
		String before = JsonPath.read(body(call(k.owner(), get("/api/v1/owner/orders"))), "$.version");

		send(k.owner(), post(url + "/accept"), "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.prepMinutes").value("Choose a preparation time: 10, 15, 20, 30, 45 minutes."));
		send(k.owner(), post(url + "/accept"), "{\"prepMinutes\": 25}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.prepMinutes").exists());
		send(k.owner(), post(url + "/accept"), "{\"prepMinutes\": \"soon\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.prepMinutes").value("Use a whole number."));
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PLACED);

		send(k.owner(), post(url + "/accept"), "{\"prepMinutes\": 20}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.readyBy").value("2026-10-04T19:50:00+05:30"));
		assertThat(reload(order).getEstimatedDeliveryAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 20, 0));
		String after = JsonPath.read(body(call(k.owner(), get("/api/v1/owner/orders"))), "$.version");
		assertThat(after).isNotEqualTo(before);

		call(k.owner(), post(url + "/ready")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
		call(k.owner(), post(url + "/preparing")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PREPARING"));
		call(k.owner(), post(url + "/ready")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));
		call(k.owner(), post(url + "/ready")).andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"))
			.andExpect(jsonPath("$.detail").value(order.getCode() + " is now “Ready for pickup”, so nothing changed."));
		send(k.owner(), post(url + "/accept"), "{\"prepMinutes\": 1}").andExpect(status().isConflict());
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);

		// the customer sees the kitchen's progress
		call(k.owner(), get("/api/v1/owner/orders")).andExpect(jsonPath("$.orders[0].status").value("READY_FOR_PICKUP"));
	}

	@Test
	void rejectNeedsAReasonAndOrdersThatMovedOnAreLeftAlone() throws Exception {
		Kitchen k = kitchen("Numbat Busy", RestaurantStatus.APPROVED);
		Order order = order(k, OrderStatus.PLACED);
		String url = "/api/v1/owner/orders/" + order.getCode() + "/reject";
		send(k.owner(), post(url), "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.reason").value("Choose a reason. The customer sees it."));
		send(k.owner(), post(url), "{\"reason\": \"Something else\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.details").value("Tell the customer why you can't take this order."));
		send(k.owner(), post(url), "{\"reason\": \"Some dishes are sold out\", \"details\": \"" + "x".repeat(201) + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.details").value("Keep it to 200 characters or fewer."));
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PLACED);

		send(k.owner(), post(url), "{\"reason\": \"Some dishes are sold out\", \"details\": \"No thali left\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"))
			.andExpect(jsonPath("$.cancelReason").value("Some dishes are sold out: No thali left"));
		assertThat(reload(order).getCancelReason()).isEqualTo("Some dishes are sold out: No thali left");

		Order cancelled = orders.placeThenCancel(orders.customer().getId(), k.dish().getId(), 1, PaymentMethod.COD);
		String cancelledUrl = "/api/v1/owner/orders/" + cancelled.getCode();
		send(k.owner(), post(cancelledUrl + "/reject"), "{\"reason\": \"We're closing soon\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.detail").value("The customer cancelled " + cancelled.getCode() + ". Nothing to cook for it."));
		send(k.owner(), post(cancelledUrl + "/reject"), "{}").andExpect(status().isConflict());
		send(k.owner(), post(cancelledUrl + "/accept"), "{\"prepMinutes\": 15}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(reload(cancelled).getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	// ---------------------------------------------------------------- owner: menu and switch

	@Test
	void dishesCanBeSoldOutAndBackInStock() throws Exception {
		Kitchen k = kitchen("Numbat Menu", RestaurantStatus.APPROVED);
		call(k.owner(), get("/api/v1/owner/menu")).andExpect(status().isOk())
			.andExpect(jsonPath("$.sections[0].name").value("Menu"))
			.andExpect(jsonPath("$.sections[0].dishes[*].name").value(contains("Numbat Menu Thali", "Numbat Menu Lassi")))
			.andExpect(jsonPath("$.sections[0].dishes[0].id").value(k.dish().getId()))
			.andExpect(jsonPath("$.sections[0].dishes[0].price").value(199.0))
			.andExpect(jsonPath("$.dishCount").value(2))
			.andExpect(jsonPath("$.soldOutCount").value(0));

		String url = "/api/v1/owner/menu/items/" + k.dish().getId() + "/availability";
		send(k.owner(), put(url), "{\"available\": false}").andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Numbat Menu Thali"))
			.andExpect(jsonPath("$.available").value(false));
		assertThat(items.findById(k.dish().getId()).orElseThrow().isAvailable()).isFalse();
		mvc.perform(get("/api/v1/restaurants/" + k.restaurant().getSlug()))
			.andExpect(jsonPath("$.menu[0].items[0].available").value(false));
		call(k.owner(), get("/api/v1/owner/menu")).andExpect(jsonPath("$.soldOutCount").value(1));

		send(k.owner(), put(url), "{\"available\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(true));
		assertThat(items.findById(k.dish().getId()).orElseThrow().isAvailable()).isTrue();
		send(k.owner(), put(url), "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.available").value("Use true (in stock) or false (sold out)."));
		send(k.owner(), put(url), "{\"available\": \"no\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.available").value("Use true or false."));
	}

	@Test
	void newOrdersCanBePausedAndResumedOnceLive() throws Exception {
		Kitchen k = kitchen("Numbat Pause", RestaurantStatus.APPROVED);
		send(k.owner(), put("/api/v1/owner/accepting-orders"), "{\"acceptingOrders\": false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptingOrders").value(false))
			.andExpect(jsonPath("$.openNow").value(true));
		assertThat(restaurants.findById(k.restaurant().getId()).orElseThrow().isAcceptingOrders()).isFalse();
		call(k.owner(), get("/api/v1/owner/orders")).andExpect(jsonPath("$.accepting.acceptingOrders").value(false));
		mvc.perform(get("/api/v1/restaurants/" + k.restaurant().getSlug()))
			.andExpect(jsonPath("$.acceptingOrders").value(false));

		send(k.owner(), put("/api/v1/owner/accepting-orders"), "{\"acceptingOrders\": true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptingOrders").value(true));
		assertThat(restaurants.findById(k.restaurant().getId()).orElseThrow().isAcceptingOrders()).isTrue();
		send(k.owner(), put("/api/v1/owner/accepting-orders"), "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.acceptingOrders").exists());

		Kitchen pending = kitchen("Numbat Pending", RestaurantStatus.PENDING);
		send(pending.owner(), put("/api/v1/owner/accepting-orders"), "{\"acceptingOrders\": false}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("You can pause or resume orders once Numbat Pending is live on Zaika."));
		send(account(Role.RESTAURANT_OWNER), put("/api/v1/owner/accepting-orders"), "{\"acceptingOrders\": false}")
			.andExpect(status().isNotFound());
	}

	@Test
	void anotherKitchensOrdersAndDishesAreNotFound() throws Exception {
		Kitchen mine = kitchen("Numbat Mine", RestaurantStatus.APPROVED);
		Kitchen theirs = kitchen("Numbat Theirs", RestaurantStatus.APPROVED);
		Order order = order(theirs, OrderStatus.PLACED);
		Order cooking = order(theirs, OrderStatus.PREPARING);
		Order unpaid = orders.create(orders.customer().getId(), mine.dish().getId(), 1, PaymentMethod.ONLINE);
		for (Order o : List.of(order, cooking, unpaid)) {
			String url = "/api/v1/owner/orders/" + o.getCode();
			call(mine.owner(), get(url)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("We couldn't find that."));
			send(mine.owner(), post(url + "/accept"), "{\"prepMinutes\": 15}").andExpect(status().isNotFound());
			send(mine.owner(), post(url + "/accept"), "{}").andExpect(status().isNotFound());
			send(mine.owner(), post(url + "/reject"), "{\"reason\": \"We're closing soon\"}")
				.andExpect(status().isNotFound());
			send(mine.owner(), post(url + "/reject"), "{}").andExpect(status().isNotFound());
			call(mine.owner(), post(url + "/preparing")).andExpect(status().isNotFound());
			call(mine.owner(), post(url + "/ready")).andExpect(status().isNotFound());
		}
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PLACED);
		assertThat(reload(cooking).getStatus()).isEqualTo(OrderStatus.PREPARING);
		assertThat(reload(unpaid).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
		assertThat(body(call(mine.owner(), get("/api/v1/owner/orders")))).doesNotContain(order.getCode());

		send(mine.owner(), put("/api/v1/owner/menu/items/" + theirs.dish().getId() + "/availability"),
				"{\"available\": false}").andExpect(status().isNotFound());
		assertThat(items.findById(theirs.dish().getId()).orElseThrow().isAvailable()).isTrue();
	}

	// ---------------------------------------------------------------- rider

	@Test
	void ridersGoOnlineSeeTheWaitingOrdersAndClaimOne() throws Exception {
		Order ready = waiting(OrderStatus.READY_FOR_PICKUP);
		Order accepted = waiting(OrderStatus.ACCEPTED);
		Account rahul = rider(RiderStatus.APPROVED);
		call(rahul, get("/api/v1/rider/dashboard")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.online").value(false))
			.andExpect(jsonPath("$.onDuty").value(false))
			.andExpect(jsonPath("$.currentDelivery").value(nullValue()))
			.andExpect(jsonPath("$.availableOrders", hasSize(0)));
		send(rahul, post("/api/v1/rider/orders/" + ready.getCode() + "/claim"), "")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Go online to take orders."));

		send(rahul, put("/api/v1/rider/online"), "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.online").exists());
		send(rahul, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.online").value(true))
			.andExpect(jsonPath("$.onDuty").value(true))
			.andExpect(jsonPath("$.availableOrders[0].code").value(ready.getCode()))
			.andExpect(jsonPath("$.availableOrders[1].code").value(accepted.getCode()));
		call(rahul, get("/api/v1/rider/orders/available")).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].code").value(ready.getCode()))
			.andExpect(jsonPath("$[0].pickup.name").value("Numbat Rider Kitchen"))
			.andExpect(jsonPath("$[0].pickup.area").value("Rajarampuri"))
			.andExpect(jsonPath("$[0].dropStreet").value("Rajarampuri 5th Lane"))
			.andExpect(jsonPath("$[0].itemCount").value(1))
			.andExpect(jsonPath("$[0].readyNow").value(true))
			.andExpect(jsonPath("$[0].cashOrder").value(true))
			.andExpect(jsonPath("$[0].payout").value(54.0))
			.andExpect(jsonPath("$[1].readyNow").value(false))
			.andExpect(jsonPath("$[1].status").value("ACCEPTED"));

		call(rahul, post("/api/v1/rider/orders/" + ready.getCode() + "/claim")).andExpect(status().isOk())
			.andExpect(jsonPath("$.currentDelivery.code").value(ready.getCode()))
			.andExpect(jsonPath("$.currentDelivery.status").value("READY_FOR_PICKUP"))
			.andExpect(jsonPath("$.currentDelivery.pickup.address").value("12, Main Road, Rajarampuri, Kolhapur 416008"))
			.andExpect(jsonPath("$.currentDelivery.pickup.phone").value("9876543210"))
			.andExpect(jsonPath("$.currentDelivery.drop.name").value("Riya Shinde"))
			.andExpect(jsonPath("$.currentDelivery.drop.phone").value("+91 98••• ••210"))
			.andExpect(jsonPath("$.currentDelivery.drop.address").value(OrderFixtures.ADDRESS.oneLine()))
			.andExpect(jsonPath("$.currentDelivery.items[0].name").value("Numbat Misal"))
			.andExpect(jsonPath("$.currentDelivery.cashToCollect").value(243.95))
			.andExpect(jsonPath("$.currentDelivery.payout").value(54.0))
			.andExpect(jsonPath("$.availableOrders", hasSize(0)));
		assertThat(reload(ready).getRider().getId()).isEqualTo(rahul.id());
		String json = body(call(rahul, get("/api/v1/rider/dashboard")));
		assertThat(json).doesNotContain("\"" + reload(ready).getDeliveryOtp() + "\"");
		call(rahul, post("/api/v1/rider/orders/" + accepted.getCode() + "/claim")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Finish your current delivery before you take another order."));

		Account sagar = rider(RiderStatus.APPROVED);
		send(sagar, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.availableOrders[*].code").value(not(hasItem(ready.getCode()))));
		call(sagar, post("/api/v1/rider/orders/" + ready.getCode() + "/claim")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Another partner just took " + ready.getCode() + ". Pick another order."));

		// going offline keeps the delivery
		send(rahul, put("/api/v1/rider/online"), "{\"online\": false}").andExpect(status().isOk())
			.andExpect(jsonPath("$.online").value(false))
			.andExpect(jsonPath("$.currentDelivery.code").value(ready.getCode()));

		Account pending = rider(RiderStatus.PENDING);
		send(pending, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("You can go online once your account is approved."));
		call(rider(RiderStatus.SUSPENDED), get("/api/v1/rider/dashboard")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"))
			.andExpect(jsonPath("$.statusReason").value("Three late deliveries"));
	}

	@Test
	void pickUpAndDeliverWithTheCustomersCodeThenSeeTheEarnings() throws Exception {
		Order cooking = waiting(OrderStatus.PREPARING);
		Account rahul = rider(RiderStatus.APPROVED);
		send(rahul, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isOk());
		call(rahul, post("/api/v1/rider/orders/" + cooking.getCode() + "/claim")).andExpect(status().isOk());
		String url = "/api/v1/rider/orders/" + cooking.getCode();
		call(rahul, post(url + "/pickup")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The kitchen hasn't marked " + cooking.getCode()
					+ " ready yet. Pick it up once it's packed."));
		send(rahul, post(url + "/deliver"), "{\"otp\": \"1234\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Mark " + cooking.getCode() + " picked up before you deliver it."));

		Account owner = signIn(users.findByEmailIgnoreCase("numbat-rider-kitchen@fixtures.zaika.test").orElseThrow());
		call(owner, post("/api/v1/owner/orders/" + cooking.getCode() + "/ready")).andExpect(status().isOk())
			.andExpect(jsonPath("$.riderName").value("Sagar Patil"));
		call(rahul, post(url + "/pickup")).andExpect(status().isOk())
			.andExpect(jsonPath("$.currentDelivery.status").value("OUT_FOR_DELIVERY"));

		String otp = reload(cooking).getDeliveryOtp();
		String wrong = otp.equals("0000") ? "1111" : "0000";
		send(rahul, post(url + "/deliver"), "{\"otp\": \"12\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.otp").value("Enter the 4-digit code from the customer's tracking page."));
		send(rahul, post(url + "/deliver"), "{\"otp\": \"" + wrong + "\"}").andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.errors.otp").value("That code doesn't match. 4 tries left."));
		assertThat(reload(cooking).otpAttemptsLeft()).isEqualTo(4);
		assertThat(reload(cooking).getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);

		send(rahul, post(url + "/deliver"), "{\"otp\": \"" + otp.charAt(0) + otp.charAt(1) + " " + otp.substring(2) + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(cooking.getCode()))
			.andExpect(jsonPath("$.amount").value(54.0))
			.andExpect(jsonPath("$.basePayout").value(30.0))
			.andExpect(jsonPath("$.feeShare").value(24.0))
			.andExpect(jsonPath("$.cashCollected").value(243.95))
			.andExpect(jsonPath("$.deliveredAt").value("2026-10-04T19:30:00+05:30"))
			.andExpect(jsonPath("$.message").value(cooking.getCode()
					+ " delivered. You earned ₹54 and collected ₹243.95 in cash."));
		Order delivered = reload(cooking);
		assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
		assertThat(delivered.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		send(rahul, post(url + "/deliver"), "{\"otp\": \"" + otp + "\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(cooking.getCode() + " is already delivered."));
		call(rahul, get("/api/v1/rider/dashboard")).andExpect(jsonPath("$.currentDelivery").value(nullValue()));

		call(rahul, get("/api/v1/rider/earnings")).andExpect(status().isOk())
			.andExpect(jsonPath("$.today").value("2026-10-04"))
			.andExpect(jsonPath("$.weekStart").value("2026-09-28"))
			.andExpect(jsonPath("$.monthStart").value("2026-10-01"))
			.andExpect(jsonPath("$.todayTotals.deliveries").value(1))
			.andExpect(jsonPath("$.todayTotals.earned").value(54.0))
			.andExpect(jsonPath("$.week.deliveries").value(1))
			.andExpect(jsonPath("$.month.earned").value(54.0))
			.andExpect(jsonPath("$.cashInHand.cash").value(243.95))
			.andExpect(jsonPath("$.cashInHand.cashOrders").value(1))
			.andExpect(jsonPath("$.history.items", hasSize(1)))
			.andExpect(jsonPath("$.history.items[0].code").value(cooking.getCode()))
			.andExpect(jsonPath("$.history.items[0].restaurant").value("Numbat Rider Kitchen"))
			.andExpect(jsonPath("$.history.items[0].amount").value(54.0))
			.andExpect(jsonPath("$.history.page").value(1))
			.andExpect(jsonPath("$.history.size").value(20))
			.andExpect(jsonPath("$.history.totalItems").value(1));
		call(rahul, get("/api/v1/rider/earnings?page=2")).andExpect(status().isOk())
			.andExpect(jsonPath("$.history.items", hasSize(0)));
		call(rahul, get("/api/v1/rider/earnings?page=0")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.page").value("Pages start at 1."));

		Account fresh = rider(RiderStatus.APPROVED);
		call(fresh, get("/api/v1/rider/earnings")).andExpect(status().isOk())
			.andExpect(jsonPath("$.todayTotals.deliveries").value(0))
			.andExpect(jsonPath("$.todayTotals.earned").value(0.0))
			.andExpect(jsonPath("$.history.items", hasSize(0)));
	}

	@Test
	void anotherRidersDeliveriesAreNotFound() throws Exception {
		Order order = waiting(OrderStatus.READY_FOR_PICKUP);
		Account rahul = rider(RiderStatus.APPROVED);
		send(rahul, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isOk());
		call(rahul, post("/api/v1/rider/orders/" + order.getCode() + "/claim")).andExpect(status().isOk());

		Account sagar = rider(RiderStatus.APPROVED);
		String url = "/api/v1/rider/orders/" + order.getCode();
		call(sagar, post(url + "/pickup")).andExpect(status().isNotFound());
		send(sagar, post(url + "/deliver"), "{\"otp\": \"" + reload(order).getDeliveryOtp() + "\"}")
			.andExpect(status().isNotFound());
		assertThat(body(call(sagar, get("/api/v1/rider/dashboard")))).doesNotContain(order.getCode());
		assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
		send(sagar, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isOk());
		call(sagar, post("/api/v1/rider/orders/ZK-26-999999/claim")).andExpect(status().isNotFound());
		Order unpaid = orders.create(orders.customer().getId(), misal.getId(), 1, PaymentMethod.ONLINE);
		call(sagar, post("/api/v1/rider/orders/" + unpaid.getCode() + "/claim")).andExpect(status().isNotFound());

		Account noProfile = account(Role.DELIVERY_PARTNER);
		call(noProfile, get("/api/v1/rider/dashboard")).andExpect(status().isNotFound());
		call(noProfile, get("/api/v1/rider/orders/available")).andExpect(status().isNotFound());
		call(noProfile, get("/api/v1/rider/earnings")).andExpect(status().isNotFound());
		send(noProfile, put("/api/v1/rider/online"), "{\"online\": true}").andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- security

	@Test
	void onlyOwnersAndRidersWithATokenGetIntoTheirOwnArea() throws Exception {
		Account customer = account(Role.CUSTOMER);
		Account owner = account(Role.RESTAURANT_OWNER);
		Account rider = account(Role.DELIVERY_PARTNER);
		String json = "application/json";
		List<Supplier<MockHttpServletRequestBuilder>> ownerRequests = List.of(() -> get("/api/v1/owner/orders"),
				() -> get("/api/v1/owner/orders/ZK-26-000001"),
				() -> post("/api/v1/owner/orders/ZK-26-000001/accept").contentType(json).content("{\"prepMinutes\": 15}"),
				() -> post("/api/v1/owner/orders/ZK-26-000001/reject").contentType(json).content("{}"),
				() -> post("/api/v1/owner/orders/ZK-26-000001/preparing"),
				() -> post("/api/v1/owner/orders/ZK-26-000001/ready"), () -> get("/api/v1/owner/menu"),
				() -> put("/api/v1/owner/menu/items/" + misal.getId() + "/availability").contentType(json)
					.content("{\"available\": false}"),
				() -> put("/api/v1/owner/accepting-orders").contentType(json).content("{\"acceptingOrders\": false}"));
		List<Supplier<MockHttpServletRequestBuilder>> riderRequests = List.of(() -> get("/api/v1/rider/dashboard"),
				() -> get("/api/v1/rider/orders/available"),
				() -> put("/api/v1/rider/online").contentType(json).content("{\"online\": true}"),
				() -> post("/api/v1/rider/orders/ZK-26-000001/claim"),
				() -> post("/api/v1/rider/orders/ZK-26-000001/pickup"),
				() -> post("/api/v1/rider/orders/ZK-26-000001/deliver").contentType(json).content("{\"otp\": \"1234\"}"),
				() -> get("/api/v1/rider/earnings"));
		for (Supplier<MockHttpServletRequestBuilder> request : ownerRequests) {
			mvc.perform(request.get()).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM));
			for (Account other : List.of(customer, rider)) {
				call(other, request.get()).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.detail").value("Your account can't use this."));
			}
		}
		for (Supplier<MockHttpServletRequestBuilder> request : riderRequests) {
			mvc.perform(request.get()).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM));
			for (Account other : List.of(customer, owner)) {
				call(other, request.get()).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.detail").value("Your account can't use this."));
			}
		}
		assertThat(items.findById(misal.getId()).orElseThrow().isAvailable()).isTrue();
	}

}
