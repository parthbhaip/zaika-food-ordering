package com.zaika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponType;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.AddressRepository;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * The customer API: {@code /api/v1/addresses}, {@code /cart}, {@code /orders}. Shares OrderPagesTest's context and
 * database (Spring's context cache is full), so its restaurants are all "Wombat ..." and every test signs up its own
 * customer. Clock: Sunday 4 Oct 2026, 19:30 IST. Dishes cost ₹199; delivery ₹30, platform fee ₹5, GST 5%.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerApiTest {

	static final String BASE = "http://localhost:8080";

	static final String PASSWORD = "Wombat@2026";

	static final String PROBLEM = "application/problem+json";

	static final String ADDRESS = """
			{"label": "HOME", "house": "Flat 4B, Shree Apartments", "street": "Rajarampuri 5th Lane",
			 "landmark": "Ganesh Mandir", "city": "Kolhapur", "pincode": "416 008"}""";

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	AddressRepository addressRows;

	@Autowired
	OrderRepository orderRows;

	@Autowired
	CouponRepository coupons;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ZaikaUserDetailsService userDetailsService;

	MenuItem misal;

	MenuItem thali;

	MenuItem kulfi;

	MenuItem tikka;

	MenuItem biryani;

	@BeforeAll
	void createRestaurants() {
		Restaurant kitchen = fixtures.restaurant("wombat-kitchen", "Wombat Kitchen").cuisines("Kolhapuri")
			.minimumOrder(150).dishes("Wombat Misal", "Wombat Thali", "Wombat Kulfi").save();
		misal = dish(kitchen, "Wombat Misal");
		thali = dish(kitchen, "Wombat Thali");
		kulfi = dish(kitchen, "Wombat Kulfi");
		kulfi.setAvailable(false);
		items.save(kulfi);
		tikka = dish(fixtures.restaurant("wombat-grill", "Wombat Grill").cuisines("North Indian")
			.dishes("Wombat Tikka").save(), "Wombat Tikka");
		biryani = dish(fixtures.restaurant("wombat-night", "Wombat Night Dhaba").cuisines("Biryani")
			.hours("22:00", "23:30").dishes("Wombat Biryani").save(), "Wombat Biryani");
		Coupon coupon = new Coupon("WOMBAT50", "Flat ₹50 off at Wombat Kitchen.", CouponType.FLAT,
				new BigDecimal("50"), MutableClock.DEFAULT_NOW.minusDays(1));
		coupon.setRestaurant(kitchen);
		coupons.save(coupon);
	}

	MenuItem dish(Restaurant restaurant, String name) {
		return items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId()).stream()
			.filter(item -> item.getName().equals(name))
			.findFirst()
			.orElseThrow();
	}

	// ---------------------------------------------------------------- helpers

	/** A signed-in account of that role: its id and access token. */
	record Account(long id, String token) {
	}

	Account account(Role role) throws Exception {
		String email = "api-" + UUID.randomUUID() + "@wombat.zaika.test";
		User user = users.save(new User("Riya Shinde", email, "9876543210", passwordEncoder.encode(PASSWORD), role));
		MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return new Account(user.getId(), JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken"));
	}

	Account customer() throws Exception {
		return account(Role.CUSTOMER);
	}

	ResultActions call(Account who, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.token()));
	}

	ResultActions send(Account who, MockHttpServletRequestBuilder request, String json) throws Exception {
		return call(who, request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	long addAddress(Account who) throws Exception {
		MvcResult result = send(who, post("/api/v1/addresses"), ADDRESS).andExpect(status().isCreated()).andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	ResultActions setQuantity(Account who, MenuItem item, int quantity) throws Exception {
		return send(who, put("/api/v1/cart/items/" + item.getId()), "{\"quantity\": " + quantity + "}");
	}

	String checkout(long addressId, String method, String total, String key) {
		return """
				{"addressId": %d, "paymentMethod": "%s", "deliveryNote": "Ring twice", "idempotencyKey": "%s",
				 "expectedTotal": %s}""".formatted(addressId, method, key, total);
	}

	static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	// ---------------------------------------------------------------- addresses

	@Test
	void addressesCanBeAddedListedEditedAndDeletedWithOneDefault() throws Exception {
		Account riya = customer();
		call(riya, get("/api/v1/addresses")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

		MvcResult first = send(riya, post("/api/v1/addresses"), ADDRESS)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.label").value("HOME"))
			.andExpect(jsonPath("$.pincode").value("416008"))
			.andExpect(jsonPath("$.defaultAddress").value(true))
			.andExpect(jsonPath("$.oneLine")
				.value("Flat 4B, Shree Apartments, Rajarampuri 5th Lane, near Ganesh Mandir, Kolhapur 416008"))
			.andReturn();
		long home = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();
		assertThat(first.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/addresses/" + home);

		MvcResult second = send(riya, post("/api/v1/addresses"), """
				{"label": "WORK", "house": "Office 12", "street": "Station Road", "city": "Kolhapur",
				 "pincode": "416001", "makeDefault": true}""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.defaultAddress").value(true))
			.andExpect(jsonPath("$.landmark").value(nullValue()))
			.andReturn();
		long work = ((Number) JsonPath.read(second.getResponse().getContentAsString(), "$.id")).longValue();

		call(riya, get("/api/v1/addresses")).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].label", contains("WORK", "HOME")))
			.andExpect(jsonPath("$[*].defaultAddress", contains(true, false)));

		send(riya, put("/api/v1/addresses/" + home), ADDRESS.replace("Flat 4B", "Flat 7C").replace("HOME", "OTHER"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.house").value("Flat 7C, Shree Apartments"))
			.andExpect(jsonPath("$.label").value("OTHER"))
			.andExpect(jsonPath("$.defaultAddress").value(false));
		call(riya, get("/api/v1/addresses/" + home)).andExpect(status().isOk())
			.andExpect(jsonPath("$.house").value("Flat 7C, Shree Apartments"));

		call(riya, delete("/api/v1/addresses/" + work)).andExpect(status().isNoContent());
		call(riya, get("/api/v1/addresses")).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id", contains((int) home)))
			.andExpect(jsonPath("$[0].defaultAddress").value(true));
	}

	@Test
	void invalidAddressesAreRefusedFieldByFieldWithTheWebsitesMessages() throws Exception {
		Account riya = customer();
		send(riya, post("/api/v1/addresses"), "{\"label\": \"HOME\", \"house\": \" \", \"street\": \"MG Road\", "
				+ "\"pincode\": \"016008\"}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.errors.house").value("Enter your house, flat or building."))
			.andExpect(jsonPath("$.errors.pincode").value("Enter a valid 6-digit PIN code, like 416008."));
		send(riya, post("/api/v1/addresses"), ADDRESS.replace("\"HOME\"", "\"OFFICE\""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.label").value("Use one of: HOME, WORK, OTHER."));
		send(riya, post("/api/v1/addresses"), "{\"house\": ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("The request body is missing or is not valid JSON."));
		assertThat(addressRows.countByUserId(riya.id())).isZero();

		for (int i = 0; i < 10; i++) {
			addAddress(riya);
		}
		send(riya, post("/api/v1/addresses"), ADDRESS)
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail").value("You can save up to 10 addresses. Delete one to add another."));
		assertThat(addressRows.countByUserId(riya.id())).isEqualTo(10);
	}

	@Test
	void anotherCustomersAddressIsNotFound() throws Exception {
		Account riya = customer();
		Account other = customer();
		long theirs = addAddress(other);
		call(riya, get("/api/v1/addresses/" + theirs)).andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM));
		send(riya, put("/api/v1/addresses/" + theirs), ADDRESS.replace("Flat 4B", "Hacked")).andExpect(status().isNotFound());
		call(riya, delete("/api/v1/addresses/" + theirs)).andExpect(status().isNotFound());
		call(other, get("/api/v1/addresses/" + theirs)).andExpect(status().isOk())
			.andExpect(jsonPath("$.house").value("Flat 4B, Shree Apartments"));
	}

	// ---------------------------------------------------------------- cart

	@Test
	void theCartIsPricedLikeTheWebsiteAndChangesDishByDish() throws Exception {
		Account riya = customer();
		call(riya, get("/api/v1/cart")).andExpect(status().isOk())
			.andExpect(jsonPath("$.restaurant").value(nullValue()))
			.andExpect(jsonPath("$.lines", hasSize(0)))
			.andExpect(jsonPath("$.bill.grandTotal").value(0))
			.andExpect(jsonPath("$.canCheckout").value(false));

		String added = body(setQuantity(riya, misal, 2).andExpect(status().isOk())
			.andExpect(jsonPath("$.restaurant.slug").value("wombat-kitchen"))
			.andExpect(jsonPath("$.restaurant.name").value("Wombat Kitchen"))
			.andExpect(jsonPath("$.restaurant.minimumOrder").value(150))
			.andExpect(jsonPath("$.takingOrders").value(true))
			.andExpect(jsonPath("$.lines[0].itemId").value(misal.getId()))
			.andExpect(jsonPath("$.lines[0].name").value("Wombat Misal"))
			.andExpect(jsonPath("$.lines[0].quantity").value(2))
			.andExpect(jsonPath("$.lines[0].lineTotal").value(398))
			.andExpect(jsonPath("$.lines[0].imageUrl").value(startsWith(BASE + "/")))
			.andExpect(jsonPath("$.itemCount").value(2))
			.andExpect(jsonPath("$.offers[0].code").value("WOMBAT50"))
			.andExpect(jsonPath("$.freeDeliveryGap").value(102))
			.andExpect(jsonPath("$.minimumOrderShortfall").value(0))
			.andExpect(jsonPath("$.canCheckout").value(true)));
		assertThat(added).contains("\"bill\":{\"itemTotal\":398.00,\"discount\":0.00,\"deliveryFee\":30.00,"
				+ "\"platformFee\":5.00,\"gst\":19.90,\"grandTotal\":452.90}");

		setQuantity(riya, thali, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.lines", hasSize(2)))
			.andExpect(jsonPath("$.itemCount").value(3))
			.andExpect(jsonPath("$.bill.deliveryFee").value(0))
			.andExpect(jsonPath("$.freeDeliveryGap").value(nullValue()));
		setQuantity(riya, misal, 3).andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(4));
		call(riya, delete("/api/v1/cart/items/" + misal.getId())).andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[*].name", contains("Wombat Thali")));
		setQuantity(riya, thali, 0).andExpect(status().isOk()).andExpect(jsonPath("$.lines", hasSize(0)));

		setQuantity(riya, misal, 1).andExpect(status().isOk());
		send(riya, put("/api/v1/cart/note"), "{\"instructions\": \"  Less spicy, please \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.instructions").value("Less spicy, please"));
		send(riya, put("/api/v1/cart/note"), "{\"instructions\": \"" + "x".repeat(251) + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.instructions").value("Keep the note to 250 characters or fewer."));
		call(riya, get("/api/v1/cart")).andExpect(jsonPath("$.minimumOrderShortfall").value(0))
			.andExpect(jsonPath("$.instructions").value("Less spicy, please"));

		call(riya, delete("/api/v1/cart")).andExpect(status().isNoContent());
		call(riya, get("/api/v1/cart")).andExpect(jsonPath("$.lines", hasSize(0)));
		send(riya, put("/api/v1/cart/note"), "{\"instructions\": \"Hi\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("EMPTY_CART"));
	}

	@Test
	void theCartRulesAnswerWithProblems() throws Exception {
		Account riya = customer();
		setQuantity(riya, misal, 21).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.quantity").value("You can order up to 20 of a dish at a time."));
		setQuantity(riya, misal, -1).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.quantity").value("Send a quantity from 0 to 20."));
		send(riya, put("/api/v1/cart/items/" + misal.getId()), "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.quantity").value("Send a quantity from 0 to 20."));
		send(riya, put("/api/v1/cart/items/" + misal.getId()), "{\"quantity\": \"two\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.quantity").value("Use a whole number."));
		setQuantity(riya, kulfi, 1).andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.detail").value("Wombat Kulfi just sold out."))
			.andExpect(jsonPath("$.reason").value("SOLD_OUT"));
		setQuantity(riya, biryani, 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Wombat Night Dhaba is not taking orders right now."))
			.andExpect(jsonPath("$.reason").value("CLOSED"));
		send(riya, put("/api/v1/cart/items/999999"), "{\"quantity\": 1}").andExpect(status().isNotFound());

		setQuantity(riya, misal, 2).andExpect(status().isOk());
		setQuantity(riya, tikka, 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("OTHER_RESTAURANT"))
			.andExpect(jsonPath("$.cartRestaurant").value("Wombat Kitchen"))
			.andExpect(jsonPath("$.detail").value("Your cart has dishes from Wombat Kitchen. Send \"startNew\": true "
					+ "to empty it and start a new cart from Wombat Grill."));
		call(riya, get("/api/v1/cart")).andExpect(jsonPath("$.restaurant.slug").value("wombat-kitchen"));
		send(riya, put("/api/v1/cart/items/" + tikka.getId()), "{\"quantity\": 1, \"startNew\": true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.restaurant.slug").value("wombat-grill"))
			.andExpect(jsonPath("$.lines[*].name", contains("Wombat Tikka")));
	}

	@Test
	void couponsAreAppliedCheckedAndRemoved() throws Exception {
		Account riya = customer();
		send(riya, post("/api/v1/cart/coupon"), "{\"code\": \"WOMBAT50\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.code").isNotEmpty());
		setQuantity(riya, misal, 2).andExpect(status().isOk());
		send(riya, post("/api/v1/cart/coupon"), "{\"code\": \"NOSUCH\"}").andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.errors.code").isNotEmpty());
		send(riya, post("/api/v1/cart/coupon"), "{\"code\": \" wombat50 \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.coupon.code").value("WOMBAT50"))
			.andExpect(jsonPath("$.coupon.discount").value(50))
			.andExpect(jsonPath("$.bill.discount").value(50))
			.andExpect(jsonPath("$.bill.gst").value(17.4))
			.andExpect(jsonPath("$.bill.grandTotal").value(400.4));
		call(riya, get("/api/v1/cart")).andExpect(jsonPath("$.coupon.code").value("WOMBAT50"));
		call(riya, delete("/api/v1/cart/coupon")).andExpect(status().isOk())
			.andExpect(jsonPath("$.coupon").value(nullValue()))
			.andExpect(jsonPath("$.bill.grandTotal").value(452.9));
	}

	@Test
	void theApiAndTheWebsiteShareOneCart() throws Exception {
		Account riya = customer();
		setQuantity(riya, thali, 3).andExpect(status().isOk());
		String page = mvc.perform(get("/cart").with(user(userDetailsService.loadUserByUsername(
				users.findById(riya.id()).orElseThrow().getEmail()))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(page).contains("Wombat Thali").contains("Wombat Kitchen");
	}

	// ---------------------------------------------------------------- placing orders

	@Test
	void aCashOrderIsPlacedOnceAndTheCartEmpties() throws Exception {
		Account riya = customer();
		long address = addAddress(riya);
		setQuantity(riya, misal, 2).andExpect(status().isOk());
		send(riya, put("/api/v1/cart/note"), "{\"instructions\": \"No onions\"}").andExpect(status().isOk());
		String key = UUID.randomUUID().toString();

		MvcResult placed = send(riya, post("/api/v1/orders"), checkout(address, "COD", "452.90", key))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value(startsWith("ZK-26-")))
			.andExpect(jsonPath("$.status").value("PLACED"))
			.andExpect(jsonPath("$.statusLabel").value("Placed"))
			.andExpect(jsonPath("$.restaurant.slug").value("wombat-kitchen"))
			.andExpect(jsonPath("$.items[0].name").value("Wombat Misal"))
			.andExpect(jsonPath("$.items[0].quantity").value(2))
			.andExpect(jsonPath("$.items[0].unitPrice").value(199))
			.andExpect(jsonPath("$.bill.grandTotal").value(452.9))
			.andExpect(jsonPath("$.paymentMethod").value("COD"))
			.andExpect(jsonPath("$.paymentStatus").value("PENDING"))
			.andExpect(jsonPath("$.paymentUrl").value(nullValue()))
			.andExpect(jsonPath("$.deliveryAddress.oneLine").value(startsWith("Flat 4B")))
			.andExpect(jsonPath("$.restaurantNote").value("No onions"))
			.andExpect(jsonPath("$.deliveryNote").value("Ring twice"))
			.andExpect(jsonPath("$.placedAt").value("2026-10-04T19:30:00+05:30"))
			.andExpect(jsonPath("$.estimatedDeliveryAt").value("2026-10-04T20:00:00+05:30"))
			.andExpect(jsonPath("$.canCancel").value(true))
			.andExpect(jsonPath("$.canReview").value(false))
			.andReturn();
		String code = JsonPath.read(placed.getResponse().getContentAsString(), "$.code");
		assertThat(placed.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/orders/" + code);
		call(riya, get("/api/v1/cart")).andExpect(jsonPath("$.lines", hasSize(0)));

		send(riya, post("/api/v1/orders"), checkout(address, "COD", "452.90", key.toUpperCase()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code));
		assertThat(orderRows.findByCustomerIdOrderByCreatedAtDescIdDesc(riya.id(), PageRequest.of(0, 10))
			.getTotalElements()).isEqualTo(1);
		Order order = orderRows.findByCodeAndCustomerId(code, riya.id()).orElseThrow();
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
	}

	@Test
	void anOnlineOrderWaitsForPaymentOnThePaymentPage() throws Exception {
		Account riya = customer();
		long address = addAddress(riya);
		setQuantity(riya, misal, 1).andExpect(status().isOk());
		send(riya, post("/api/v1/orders"), checkout(address, "ONLINE", "243.95", UUID.randomUUID().toString()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.paymentMethod").value("ONLINE"))
			.andExpect(jsonPath("$.paymentUrl").value(startsWith(BASE + "/pay/ZK-26-")))
			.andExpect(jsonPath("$.payBy").value("2026-10-04T19:45:00+05:30"))
			.andExpect(jsonPath("$.placedAt").value(nullValue()))
			.andExpect(jsonPath("$.canCancel").value(false));
	}

	@Test
	void checkoutRefusalsPlaceNothing() throws Exception {
		Account riya = customer();
		long address = addAddress(riya);
		send(riya, post("/api/v1/orders"), "{}").andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM))
			.andExpect(jsonPath("$.errors.addressId").value("Choose one of your saved addresses (GET /api/v1/addresses)."))
			.andExpect(jsonPath("$.errors.paymentMethod").value("Choose how you want to pay: COD or ONLINE."))
			.andExpect(jsonPath("$.errors.idempotencyKey").value("Send a new random UUID for each checkout."))
			.andExpect(jsonPath("$.errors.expectedTotal")
				.value("Send the total you showed the customer (the cart's bill.grandTotal)."));
		send(riya, post("/api/v1/orders"), checkout(address, "CARD", "100", UUID.randomUUID().toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.paymentMethod").value("Use one of: COD, ONLINE."));
		send(riya, post("/api/v1/orders"), checkout(0, "COD", "100", "not-a-uuid")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.addressId").exists())
			.andExpect(jsonPath("$.errors.idempotencyKey").exists());

		send(riya, post("/api/v1/orders"), checkout(address, "COD", "100", UUID.randomUUID().toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Your cart is empty, so there is nothing to check out."))
			.andExpect(jsonPath("$.checkCart").value(true));

		setQuantity(riya, misal, 1).andExpect(status().isOk());
		send(riya, post("/api/v1/orders"), checkout(address, "COD", "999.00", UUID.randomUUID().toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Your total is now ₹243.95. Check the bill, then place your order again."))
			.andExpect(jsonPath("$.checkCart").value(false));

		long theirs = addAddress(customer());
		send(riya, post("/api/v1/orders"), checkout(theirs, "COD", "243.95", UUID.randomUUID().toString()))
			.andExpect(status().isNotFound());
		assertThat(orderRows.existsByCustomerIdAndStatusIn(riya.id(), List.of(OrderStatus.values()))).isFalse();
		call(riya, get("/api/v1/cart")).andExpect(jsonPath("$.lines", hasSize(1)));
	}

	// ---------------------------------------------------------------- reading orders

	@Test
	void theHistoryListsOnlyMyOrdersNewestFirstTenAPage() throws Exception {
		Account riya = customer();
		for (int i = 0; i < 11; i++) {
			orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		}
		Order unpaid = orders.create(riya.id(), thali.getId(), 2, PaymentMethod.ONLINE);
		orders.place(customer().id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);

		call(riya, get("/api/v1/orders")).andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(10))
			.andExpect(jsonPath("$.totalItems").value(12))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items", hasSize(10)))
			.andExpect(jsonPath("$.items[0].code").value(unpaid.getCode()))
			.andExpect(jsonPath("$.items[0].status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.items[0].items[0].name").value("Wombat Thali"))
			.andExpect(jsonPath("$.items[0].itemCount").value(2))
			.andExpect(jsonPath("$.items[0].paymentUrl").value(BASE + "/pay/" + unpaid.getCode()))
			.andExpect(jsonPath("$.items[1].paymentUrl").value(nullValue()))
			.andExpect(jsonPath("$.items[1].restaurant.name").value("Wombat Kitchen"))
			.andExpect(jsonPath("$.items[1].createdAt").value("2026-10-04T19:30:00+05:30"));
		call(riya, get("/api/v1/orders?page=2")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)));
		call(riya, get("/api/v1/orders?page=0")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.page").value("Pages start at 1."));
	}

	@Test
	void anotherCustomersOrderIsNotFoundEverywhere() throws Exception {
		Account riya = customer();
		Order theirs = orders.place(customer().id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		String code = theirs.getCode();
		call(riya, get("/api/v1/orders/" + code)).andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(PROBLEM));
		call(riya, get("/api/v1/orders/" + code + "/tracking")).andExpect(status().isNotFound());
		send(riya, post("/api/v1/orders/" + code + "/cancel"), "{\"reason\": \"Ordered by mistake\"}")
			.andExpect(status().isNotFound());
		send(riya, post("/api/v1/orders/" + code + "/cancel"), "{}").andExpect(status().isNotFound());
		send(riya, post("/api/v1/orders/" + code + "/review"), "{\"stars\": 1}").andExpect(status().isNotFound());
		send(riya, post("/api/v1/orders/" + code + "/review"), "{}").andExpect(status().isNotFound());
		call(riya, get("/api/v1/orders/ZK-26-999999")).andExpect(status().isNotFound());
	}

	@Test
	void trackingShowsTheStepsTheRiderAndTheCodeOnlyOnTheWay() throws Exception {
		Account riya = customer();
		Order placed = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		call(riya, get("/api/v1/orders/" + placed.getCode() + "/tracking")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PLACED"))
			.andExpect(jsonPath("$.headline").value("Waiting for the restaurant to accept"))
			.andExpect(jsonPath("$.live").value(true))
			.andExpect(jsonPath("$.steps", hasSize(6)))
			.andExpect(jsonPath("$.steps[0].state").value("CURRENT"))
			.andExpect(jsonPath("$.steps[0].note").value("Waiting for Wombat Kitchen to accept"))
			.andExpect(jsonPath("$.steps[1].state").value("UPCOMING"))
			.andExpect(jsonPath("$.rider").value(nullValue()))
			.andExpect(jsonPath("$.deliveryOtp").value(nullValue()))
			.andExpect(jsonPath("$.canCancel").value(true))
			.andExpect(jsonPath("$.cancelReasons[0]").value("Ordered by mistake"));

		Order onTheWay = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.OUT_FOR_DELIVERY);
		String otp = orderRows.findById(onTheWay.getId()).orElseThrow().getDeliveryOtp();
		call(riya, get("/api/v1/orders/" + onTheWay.getCode() + "/tracking")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
			.andExpect(jsonPath("$.deliveryOtp").value(otp))
			.andExpect(jsonPath("$.rider.name").value("Sagar Patil"))
			.andExpect(jsonPath("$.rider.phone").value("+91 98••• ••345"))
			.andExpect(jsonPath("$.steps[4].state").value("CURRENT"))
			.andExpect(jsonPath("$.canCancel").value(false))
			.andExpect(jsonPath("$.cancelReasons", hasSize(0)));
		String json = body(call(riya, get("/api/v1/orders/" + onTheWay.getCode())));
		assertThat(json).doesNotContain(otp).doesNotContain("9822012345");

		Order delivered = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		call(riya, get("/api/v1/orders/" + delivered.getCode() + "/tracking")).andExpect(status().isOk())
			.andExpect(jsonPath("$.live").value(false))
			.andExpect(jsonPath("$.deliveryOtp").value(nullValue()))
			.andExpect(jsonPath("$.steps[5].state").value("DONE"));
	}

	// ---------------------------------------------------------------- cancel and review

	@Test
	void aPlacedOrderCanBeCancelledWithAReasonAndNothingLater() throws Exception {
		Account riya = customer();
		Order placed = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		String url = "/api/v1/orders/" + placed.getCode() + "/cancel";
		send(riya, post(url), "{\"reason\": \"Bored\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.reason").value("Choose why you are cancelling."));
		send(riya, post(url), "{\"reason\": \"Something else\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.details").value("Tell us why you are cancelling."));

		send(riya, post(url), "{\"reason\": \"Ordered by mistake\", \"details\": \"Wrong dish\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.endedBy").value("You cancelled this order."))
			.andExpect(jsonPath("$.steps[-1:].state").value(contains("FAILED")))
			.andExpect(jsonPath("$.steps[-1:].note").value(contains("Ordered by mistake: Wrong dish")));
		assertThat(orderRows.findById(placed.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
		send(riya, post(url), "{\"reason\": \"Ordered by mistake\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		Order accepted = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.ACCEPTED);
		send(riya, post("/api/v1/orders/" + accepted.getCode() + "/cancel"), "{\"reason\": \"Ordered by mistake\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TOO_LATE"))
			.andExpect(jsonPath("$.detail").value("Too late to cancel: this order is now “Accepted”. Need help with it? "
					+ "Contact us."));
		send(riya, post("/api/v1/orders/" + accepted.getCode() + "/cancel"), "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("TOO_LATE"));
		assertThat(orderRows.findById(accepted.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ACCEPTED);

		Order unpaid = orders.create(riya.id(), misal.getId(), 1, PaymentMethod.ONLINE);
		send(riya, post("/api/v1/orders/" + unpaid.getCode() + "/cancel"), "{\"reason\": \"Ordered by mistake\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("AWAITING_PAYMENT"))
			.andExpect(jsonPath("$.paymentUrl").value(BASE + "/pay/" + unpaid.getCode()));
	}

	@Test
	void aDeliveredOrderIsRatedOnce() throws Exception {
		Account riya = customer();
		Order live = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.PREPARING);
		send(riya, post("/api/v1/orders/" + live.getCode() + "/review"), "{\"stars\": 5}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("You can rate this order once it's delivered."));
		send(riya, post("/api/v1/orders/" + live.getCode() + "/review"), "{\"stars\": 9}")
			.andExpect(status().isConflict());

		Order delivered = orders.place(riya.id(), misal.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		String url = "/api/v1/orders/" + delivered.getCode();
		call(riya, get(url)).andExpect(jsonPath("$.canReview").value(true))
			.andExpect(jsonPath("$.review").value(nullValue()))
			.andExpect(jsonPath("$.paymentStatus").value("PAID"))
			.andExpect(jsonPath("$.deliveredAt").value("2026-10-04T19:30:00+05:30"));
		send(riya, post(url + "/review"), "{\"stars\": 0}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.stars").value("Choose a rating from 1 to 5 stars."));
		send(riya, post(url + "/review"), "{\"stars\": 4, \"comment\": \"" + "y".repeat(501) + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.comment").value("Keep your review under 500 characters."));

		send(riya, post(url + "/review"), "{\"stars\": 4, \"comment\": \"Great misal, <b>spicy</b>!\"}")
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, url))
			.andExpect(jsonPath("$.stars").value(4))
			.andExpect(jsonPath("$.comment").value("Great misal, <b>spicy</b>!"))
			.andExpect(jsonPath("$.hidden").value(false));
		call(riya, get(url)).andExpect(jsonPath("$.canReview").value(false))
			.andExpect(jsonPath("$.review.stars").value(4));
		send(riya, post(url + "/review"), "{\"stars\": 1}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("You've already rated this order. Thanks for the feedback!"));
	}

	// ---------------------------------------------------------------- security

	@Test
	void onlyCustomersWithATokenGetIn() throws Exception {
		Account owner = account(Role.RESTAURANT_OWNER);
		Account rider = account(Role.DELIVERY_PARTNER);
		String json = "application/json";
		List<Supplier<MockHttpServletRequestBuilder>> requests = List.of(() -> get("/api/v1/addresses"),
				() -> post("/api/v1/addresses").contentType(json).content(ADDRESS),
				() -> get("/api/v1/addresses/1"), () -> put("/api/v1/addresses/1").contentType(json).content(ADDRESS),
				() -> delete("/api/v1/addresses/1"), () -> get("/api/v1/cart"),
				() -> put("/api/v1/cart/items/" + misal.getId()).contentType(json).content("{\"quantity\": 1}"),
				() -> delete("/api/v1/cart/items/" + misal.getId()), () -> delete("/api/v1/cart"),
				() -> put("/api/v1/cart/note").contentType(json).content("{}"),
				() -> post("/api/v1/cart/coupon").contentType(json).content("{\"code\": \"WOMBAT50\"}"),
				() -> delete("/api/v1/cart/coupon"), () -> get("/api/v1/orders"),
				() -> post("/api/v1/orders").contentType(json).content("{}"),
				() -> get("/api/v1/orders/ZK-26-000001"), () -> get("/api/v1/orders/ZK-26-000001/tracking"),
				() -> post("/api/v1/orders/ZK-26-000001/cancel").contentType(json).content("{}"),
				() -> post("/api/v1/orders/ZK-26-000001/review").contentType(json).content("{}"));
		for (Supplier<MockHttpServletRequestBuilder> request : requests) {
			mvc.perform(request.get()).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM));
			for (Account staff : List.of(owner, rider)) {
				call(staff, request.get()).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.detail").value("Your account can't use this."));
			}
		}
	}

}
