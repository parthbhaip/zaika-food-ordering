package com.zaika.order;

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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponType;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.user.AddressForm;
import com.zaika.user.AddressLabel;
import com.zaika.user.AddressService;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Checkout: the page (addresses, a new address inline, rider note, payment choice, summary and bill), who may use it,
 * placing cash and online orders (snapshots, cart emptied in the same transaction), every server-side re-check
 * (restaurant approved / open / accepting, dishes still available, minimum order, coupon still valid, total unchanged),
 * and the one-time key that stops a double submit from placing two orders. Own database; the test clock says Sunday
 * 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:checkout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutPageTest {

	static final Pattern KEY = Pattern.compile("name=\"idempotencyKey\" value=\"([^\"]+)\"");

	static final Pattern TOTAL = Pattern.compile("name=\"expectedTotal\" value=\"([^\"]+)\"");

	@Autowired
	MockMvc mvc;

	@Autowired
	CartService carts;

	@Autowired
	CouponService couponService;

	@Autowired
	CheckoutService checkout;

	@Autowired
	AddressService addresses;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	CouponRepository coupons;

	@Autowired
	OrderRepository orders;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	MutableClock clock;

	Restaurant kitchen; // ₹30 delivery, prep 20 min, open 11:00-23:00

	MenuItem thali; // ₹250

	MenuItem chai; // ₹40

	@BeforeAll
	void createRestaurant() {
		kitchen = fixtures.restaurant("checkout-kitchen", "Checkout Kitchen").deliveryFee(30).minimumOrder(100).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
		thali = items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false));
		chai = items.save(new MenuItem(menu, "Cutting Chai", new BigDecimal("40"), true));
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	// ---------------------------------------------------------------- helpers

	User customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@checkout.zaika.test", "9876543210",
				passwordEncoder.encode("Demo@1234"), Role.CUSTOMER));
	}

	/** A customer with a saved Home address and {@code quantity} x {@code dish} in the cart. */
	User customerWith(MenuItem dish, int quantity) {
		User user = customer();
		addresses.create(user.getId(), address("Flat 4B, Shree Apartments", "Rajarampuri 5th Lane"));
		carts.setQuantity(new CartOwner.Customer(user.getId()), dish.getId(), quantity, false);
		return user;
	}

	static AddressForm address(String house, String street) {
		AddressForm form = new AddressForm();
		form.setLabel(AddressLabel.HOME);
		form.setHouse(house);
		form.setStreet(street);
		form.setLandmark("Ganesh Mandir");
		form.setPincode("416008");
		return form;
	}

	long addressId(User user) {
		return addresses.list(user.getId()).get(0).getId();
	}

	RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	/** GET /checkout as the customer; returns the page's key and expected total. */
	String[] openCheckout(User customer) throws Exception {
		String html = mvc.perform(get("/checkout").with(as(customer))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return new String[] { find(KEY, html), find(TOTAL, html) };
	}

	static String find(Pattern pattern, String html) {
		Matcher m = pattern.matcher(html);
		assertThat(m.find()).as(pattern.pattern()).isTrue();
		return m.group(1);
	}

	MockHttpServletRequestBuilder placeAs(User customer, String[] page, String... params) {
		Map<String, String> form = new java.util.LinkedHashMap<>(Map.of("idempotencyKey", page[0], "expectedTotal",
				page[1], "paymentMethod", "COD", "addressId", String.valueOf(addressId(customer))));
		for (int i = 0; i < params.length; i += 2) {
			form.put(params[i], params[i + 1]); // overrides a default
		}
		MockHttpServletRequestBuilder post = post("/checkout").with(as(customer)).with(csrf());
		form.forEach(post::param);
		return post;
	}

	long orderCount(User customer) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, customer.getId());
	}

	Map<String, Object> onlyOrder(User customer) {
		return jdbc.queryForMap("SELECT * FROM orders WHERE customer_id = ?", customer.getId());
	}

	int cartCount(User customer) {
		return carts.summary(new CartOwner.Customer(customer.getId())).count();
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	// ---------------------------------------------------------------- the page

	@Test
	void theCartsCheckoutButtonLandsOnTheCheckoutPage() throws Exception {
		User me = customerWith(thali, 2);
		String cart = mvc.perform(get("/cart").with(as(me))).andReturn().getResponse().getContentAsString();
		assertThat(cart).contains("href=\"/checkout\"").contains("Proceed to checkout");

		String html = squash(mvc.perform(get("/checkout").with(as(me))).andExpect(status().isOk())
			.andExpect(view().name("order/checkout")).andReturn().getResponse().getContentAsString());

		assertThat(html).contains("<title>Checkout | Zaika</title>")
			.contains("Deliver to").contains("Flat 4B, Shree Apartments, Rajarampuri 5th Lane")
			.containsPattern("name=\"addressId\" value=\"" + addressId(me) + "\" id=\"address-\\d+\" checked")
			.contains("Add a new address").contains("name=\"newAddress.house\"")
			.contains("Note for the rider (optional)")
			.contains("Cash on delivery").contains("Pay online")
			.containsPattern("value=\"COD\" id=\"pay-cod\" checked")
			.contains("Checkout Kitchen").contains("Arrives in about 30 min")
			.contains("2 ×</span> <span>Mutton Thali").contains("₹500.00")
			.contains("₹30.00").contains("FREE").contains("₹5.00").contains("₹25.00").contains("₹530.00")
			.contains("Place order").contains("name=\"_csrf\"");
		assertThat(find(KEY, html)).matches(CheckoutForm.KEY_PATTERN);
		assertThat(find(TOTAL, html)).isEqualTo("530.00"); // 500 + free delivery (above ₹499) + 5 + 25
	}

	@Test
	void aCustomerWithoutAddressesTypesOneInline() throws Exception {
		User me = customer();
		carts.setQuantity(new CartOwner.Customer(me.getId()), thali.getId(), 1, false);

		String html = mvc.perform(get("/checkout").with(as(me))).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();

		assertThat(html).contains("type=\"hidden\" name=\"addressId\" value=\"0\"")
			.contains("We will save this address for next time.").doesNotContain("Add a new address");
	}

	@Test
	void onlySignedInCustomersCheckOut() throws Exception {
		mvc.perform(get("/checkout")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/checkout").with(csrf())).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		User owner = users.findById(kitchen.getOwner().getId()).orElseThrow();
		mvc.perform(get("/checkout").with(user(userDetails.loadUserByUsername(owner.getEmail()))))
			.andExpect(status().isForbidden());
	}

	@Test
	void aCartThatCannotBeCheckedOutGoesBackToTheCart() throws Exception {
		User empty = customer();
		mvc.perform(get("/checkout").with(as(empty))).andExpect(redirectedUrl("/cart"));

		Restaurant small = fixtures.restaurant("checkout-minimum", "Minimum Mess").minimumOrder(300).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(small.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Poha", new BigDecimal("60"), true));
		User below = customerWith(dish, 1);
		mvc.perform(get("/checkout").with(as(below))).andExpect(redirectedUrl("/cart"));

		User late = customerWith(thali, 1);
		clock.setTo(LocalDateTime.of(2026, 10, 4, 23, 30));
		mvc.perform(get("/checkout").with(as(late))).andExpect(redirectedUrl("/cart"));
	}

	// ---------------------------------------------------------------- placing orders

	@Test
	void cashOnDeliveryPlacesTheOrderAndEmptiesTheCart() throws Exception {
		User me = customerWith(thali, 2);
		carts.setQuantity(new CartOwner.Customer(me.getId()), chai.getId(), 1, false);
		carts.saveInstructions(new CartOwner.Customer(me.getId()), "Less spicy, please");
		String[] page = openCheckout(me);
		assertThat(page[1]).isEqualTo("572.00"); // 540 + free delivery + 5 + 27

		MvcResult result = mvc.perform(placeAs(me, page, "deliveryNote", "  Ring the bell twice  "))
			.andExpect(status().isFound()).andReturn();

		Map<String, Object> order = onlyOrder(me);
		String code = (String) order.get("code");
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/orders/" + code);
		assertThat(result.getFlashMap().get("flashSuccess")).isEqualTo(
				"Order placed. Checkout Kitchen will confirm it shortly.");
		assertThat(code).matches("ZK-26-\\d{6}");
		assertThat(order).containsEntry("status", "PLACED").containsEntry("payment_method", "COD")
			.containsEntry("payment_status", "PENDING").containsEntry("restaurant_note", "Less spicy, please")
			.containsEntry("delivery_note", "Ring the bell twice").containsEntry("address_house", "Flat 4B, Shree Apartments")
			.containsEntry("address_street", "Rajarampuri 5th Lane").containsEntry("address_pincode", "416008")
			.containsEntry("idempotency_key", page[0]);
		assertThat((BigDecimal) order.get("item_total")).isEqualByComparingTo("540.00");
		assertThat((BigDecimal) order.get("grand_total")).isEqualByComparingTo("572.00");
		assertThat(order.get("placed_at")).isNotNull();
		assertThat(jdbc.queryForObject("SELECT estimated_delivery_at FROM orders WHERE id = ?", LocalDateTime.class,
				order.get("id"))).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(30));
		assertThat(jdbc.queryForList("SELECT name, quantity, unit_price FROM order_items WHERE order_id = ? ORDER BY id",
				order.get("id"))).extracting(row -> row.get("name") + " x" + row.get("quantity"))
			.containsExactly("Mutton Thali x2", "Cutting Chai x1");
		assertThat(cartCount(me)).as("cart emptied").isZero();
		assertThat(carts.view(new CartOwner.Customer(me.getId())).instructions()).isNull();
	}

	@Test
	void payingOnlineLeavesTheOrderAwaitingPaymentAndGoesToThePaymentPage() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);

		MvcResult result = mvc.perform(placeAs(me, page, "paymentMethod", "ONLINE")).andReturn();

		Map<String, Object> order = onlyOrder(me);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/pay/" + order.get("code"));
		assertThat(order).containsEntry("status", "PENDING_PAYMENT").containsEntry("payment_method", "ONLINE")
			.containsEntry("payment_status", "PENDING");
		assertThat(order.get("placed_at")).isNull();
		assertThat(cartCount(me)).isZero();
	}

	@Test
	void aNewAddressIsSavedToTheBookAndSnapshotOnTheOrder() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);

		mvc.perform(placeAs(me, page, "addressId", "0", "newAddress.label", "WORK", "newAddress.house", "Office 12",
				"newAddress.street", "Station Road, Shahupuri", "newAddress.city", "Kolhapur", "newAddress.pincode",
				"416 001")).andExpect(status().isFound());

		assertThat(onlyOrder(me)).containsEntry("address_label", "WORK").containsEntry("address_house", "Office 12")
			.containsEntry("address_pincode", "416001");
		assertThat(addresses.list(me.getId())).extracting(a -> a.getHouse())
			.contains("Office 12", "Flat 4B, Shree Apartments");
	}

	@Test
	void anInvalidNewAddressShowsWhyAndKeepsWhatWasTyped() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);

		String html = squash(mvc.perform(placeAs(me, page, "addressId", "0", "newAddress.house", "Office 12",
				"newAddress.street", "", "newAddress.pincode", "12", "deliveryNote", "x".repeat(251)))
			.andExpect(status().isOk()).andExpect(view().name("order/checkout")).andReturn().getResponse()
			.getContentAsString());

		assertThat(html).contains("Your order was not placed. Fix the fields marked below.")
			.contains("Enter the street or area.").contains("Enter a valid 6-digit PIN code, like 416008.")
			.contains("Keep the note for the rider to 250 characters or fewer.")
			.contains("value=\"Office 12\"").containsPattern("value=\"0\" id=\"address-new\" checked")
			.contains("value=\"" + page[0] + "\"");
		assertThat(orderCount(me)).isZero();
		assertThat(addresses.list(me.getId())).hasSize(1);
		assertThat(cartCount(me)).isEqualTo(1);
	}

	@Test
	void someoneElsesAddressIsNotFound() throws Exception {
		User me = customerWith(thali, 1);
		User other = customerWith(chai, 3);
		String[] page = openCheckout(me);

		mvc.perform(placeAs(me, page, "addressId", String.valueOf(addressId(other)))).andExpect(status().isNotFound());

		assertThat(orderCount(me)).isZero();
		assertThat(cartCount(me)).isEqualTo(1);
	}

	@Test
	void theCheckoutNeedsItsKeyAndTheCsrfToken() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);
		mvc.perform(placeAs(me, new String[] { "", page[1] })).andExpect(status().isBadRequest());
		mvc.perform(placeAs(me, new String[] { "not-a-key", page[1] })).andExpect(status().isBadRequest());
		mvc.perform(post("/checkout").with(as(me)).param("idempotencyKey", page[0]).param("expectedTotal", page[1])
			.param("paymentMethod", "COD").param("addressId", String.valueOf(addressId(me))))
			.andExpect(status().isForbidden());
		assertThat(orderCount(me)).isZero();
	}

	// ---------------------------------------------------------------- double submits

	@Test
	void submittingTheSameFormTwicePlacesOneOrder() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);

		String first = mvc.perform(placeAs(me, page)).andReturn().getResponse().getRedirectedUrl();
		MvcResult again = mvc.perform(placeAs(me, page)).andExpect(status().isFound()).andReturn();

		assertThat(orderCount(me)).isEqualTo(1);
		assertThat(again.getResponse().getRedirectedUrl()).isEqualTo(first);
		assertThat(again.getFlashMap().get("flashInfo")).isEqualTo("This order was already placed. Here it is.");

		carts.setQuantity(new CartOwner.Customer(me.getId()), chai.getId(), 3, false);
		mvc.perform(placeAs(me, openCheckout(me))).andExpect(status().isFound());
		assertThat(orderCount(me)).as("a new checkout is a new order").isEqualTo(2);
	}

	@Test
	void twoSubmitsAtTheSameTimePlaceOneOrder() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);
		CheckoutForm form = new CheckoutForm();
		form.setIdempotencyKey(page[0]);
		form.setExpectedTotal(new BigDecimal(page[1]));
		form.setAddressId(addressId(me));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<String> submit = () -> {
			start.await();
			return checkout.placeOrder(me.getId(), form).order().getCode();
		};
		try {
			List<Future<String>> results = new ArrayList<>();
			results.add(pool.submit(submit));
			results.add(pool.submit(submit));
			start.countDown();
			assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(orderCount(me)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- server-side re-checks

	@Test
	void aKitchenThatStoppedTakingOrdersSendsTheCustomerBackToTheCart() throws Exception {
		Restaurant paused = fixtures.restaurant("checkout-paused", "Paused Kitchen").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(paused.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Vada Pav", new BigDecimal("150"), true));
		User me = customerWith(dish, 1);
		String[] page = openCheckout(me);
		paused.setAcceptingOrders(false);
		restaurants.save(paused);

		mvc.perform(placeAs(me, page)).andExpect(redirectedUrl("/cart"))
			.andExpect(flash().attribute("flashError", "Paused Kitchen is not taking orders right now. Your cart is saved."));

		assertThat(orderCount(me)).isZero();
		assertThat(cartCount(me)).isEqualTo(1);
	}

	@Test
	void aKitchenThatClosedMeanwhileSaysWhenItOpens() throws Exception {
		User me = customerWith(thali, 1);
		String[] page = openCheckout(me);
		clock.setTo(LocalDateTime.of(2026, 10, 4, 23, 5));

		mvc.perform(placeAs(me, page)).andExpect(redirectedUrl("/cart")).andExpect(flash().attribute("flashError",
				"Checkout Kitchen is closed now and opens at 11:00 AM. Your cart is saved."));
		assertThat(orderCount(me)).isZero();
	}

	@Test
	void aKitchenThatIsNoLongerApprovedCannotTakeTheOrder() throws Exception {
		Restaurant suspended = fixtures.restaurant("checkout-suspended", "Suspended Kitchen").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(suspended.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Misal", new BigDecimal("150"), true));
		User me = customerWith(dish, 1);
		String[] page = openCheckout(me);
		suspended.changeStatus(RestaurantStatus.SUSPENDED, "Hygiene check");
		restaurants.save(suspended);

		mvc.perform(placeAs(me, page)).andExpect(redirectedUrl("/cart"));

		assertThat(orderCount(me)).isZero();
		String cart = mvc.perform(get("/cart").with(as(me))).andReturn().getResponse().getContentAsString();
		assertThat(cart).contains("Suspended Kitchen is not taking orders on Zaika right now, so we emptied your cart.");
	}

	@Test
	void aRaisedMinimumOrderSendsTheCustomerBackToTheCart() throws Exception {
		Restaurant strict = fixtures.restaurant("checkout-strict", "Strict Kitchen").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(strict.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Sabudana Khichdi", new BigDecimal("120"), true));
		User me = customerWith(dish, 1);
		String[] page = openCheckout(me);
		strict.setMinimumOrder(new BigDecimal("200"));
		restaurants.save(strict);

		mvc.perform(placeAs(me, page)).andExpect(redirectedUrl("/cart")).andExpect(flash().attribute("flashError",
				"Add ₹80 more to place this order. Strict Kitchen takes orders from ₹200."));
		assertThat(orderCount(me)).isZero();
	}

	@Test
	void aDishThatSoldOutMeanwhileStopsTheOrderAndShowsTheChange() throws Exception {
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
		MenuItem lassi = items.save(new MenuItem(menu, "Sweet Lassi", new BigDecimal("80"), true));
		User me = customerWith(thali, 1);
		carts.setQuantity(new CartOwner.Customer(me.getId()), lassi.getId(), 1, false);
		String[] page = openCheckout(me);
		lassi.setAvailable(false);
		lassi = items.save(lassi);

		String html = squash(mvc.perform(placeAs(me, page)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString());

		assertThat(html).contains("Your cart changed while you were checking out.")
			.contains("Sweet Lassi just sold out, so we removed it from your cart.")
			.contains("value=\"" + page[0] + "\"").contains("name=\"expectedTotal\" value=\"297.50\"");
		assertThat(orderCount(me)).isZero();
		lassi.setAvailable(true);
		items.save(lassi);
	}

	@Test
	void aChangedTotalIsShownBeforeAnythingIsCharged() throws Exception {
		Restaurant pricey = fixtures.restaurant("checkout-fee", "Fee Kitchen").deliveryFee(30).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(pricey.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Pav Bhaji", new BigDecimal("150"), true));
		User me = customerWith(dish, 1);
		String[] page = openCheckout(me);
		assertThat(page[1]).isEqualTo("192.50");
		pricey.setDeliveryFee(new BigDecimal("45"));
		restaurants.save(pricey);

		String html = squash(mvc.perform(placeAs(me, page)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString());
		assertThat(html).contains("Your total is now ₹207.50. Check the bill, then place your order again.")
			.contains("name=\"expectedTotal\" value=\"207.50\"");
		assertThat(orderCount(me)).isZero();

		mvc.perform(placeAs(me, new String[] { page[0], "207.50" })).andExpect(status().isFound());
		assertThat(orderCount(me)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- coupons

	@Test
	void anAppliedCouponIsRedeemedAndLinkedToTheOrder() throws Exception {
		Coupon coupon = coupons.save(new Coupon("CHECK75", "₹75 off.", CouponType.FLAT, new BigDecimal("75"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = customerWith(thali, 2);
		assertThat(couponService.apply(me.getId(), "CHECK75").applied()).isTrue();
		String[] page = openCheckout(me);
		assertThat(page[1]).isEqualTo("451.25"); // 500 - 75 + free delivery (measured before the discount) + 5 + 21.25

		mvc.perform(placeAs(me, page)).andExpect(status().isFound());

		Map<String, Object> order = onlyOrder(me);
		assertThat(order).containsEntry("coupon_code", "CHECK75");
		assertThat((BigDecimal) order.get("discount")).isEqualByComparingTo("75.00");
		assertThat(jdbc.queryForList("SELECT order_id FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).containsExactly((Long) order.get("id"));
	}

	@Test
	void aCouponThatStoppedWorkingStopsTheOrderAndRecordsNothing() throws Exception {
		Coupon coupon = coupons.save(new Coupon("GONE50", "₹50 off.", CouponType.FLAT, new BigDecimal("50"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = customerWith(thali, 2);
		couponService.apply(me.getId(), "GONE50");
		String[] page = openCheckout(me);
		coupon.setActive(false);
		coupons.save(coupon);

		String html = squash(mvc.perform(placeAs(me, page)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString());

		assertThat(html).contains("We could not use your coupon. GONE50 is not available any more. Try another offer.")
			.contains("We removed GONE50.").contains("name=\"expectedTotal\" value=\"530.00\"");
		assertThat(orderCount(me)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isZero();
		assertThat(cartCount(me)).isEqualTo(2);
	}

	@Test
	void aFailureLateInTheTransactionUndoesTheCouponUseAndTheNewAddress() throws Exception {
		Restaurant late = fixtures.restaurant("checkout-late", "Late Kitchen").deliveryFee(30).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(late.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Tambda Rassa", new BigDecimal("300"), false));
		Coupon coupon = coupons.save(new Coupon("LATE20", "₹20 off.", CouponType.FLAT, new BigDecimal("20"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = customerWith(dish, 1);
		couponService.apply(me.getId(), "LATE20");
		String[] page = openCheckout(me);

		mvc.perform(placeAs(me, new String[] { page[0], "1.00" }, "addressId", "0", "newAddress.house", "Office 12",
				"newAddress.street", "Station Road", "newAddress.city", "Kolhapur", "newAddress.pincode", "416001"))
			.andExpect(status().isOk());

		assertThat(orderCount(me)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isZero();
		assertThat(addresses.list(me.getId())).hasSize(1);
		assertThat(cartCount(me)).isEqualTo(1);
	}

}
