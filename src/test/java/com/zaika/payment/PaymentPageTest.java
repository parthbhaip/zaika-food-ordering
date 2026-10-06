package com.zaika.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponType;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.AddressForm;
import com.zaika.user.AddressLabel;
import com.zaika.user.AddressService;
import com.zaika.user.User;

/**
 * The test-mode payment page: where online checkouts land, the banner, UPI and Card tabs with format checks, paying
 * (PLACED + PAID, a Payment row with only a masked instrument), a simulated decline and the three ways on (retry, cash
 * on delivery, cancel), the 15-minute expiry (job and on the spot), coupon uses given back, who may use it, and one
 * charge for two simultaneous submits. Own database; the test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentPageTest {

	static final Pattern KEY = Pattern.compile("name=\"idempotencyKey\" value=\"([^\"]+)\"");

	static final Pattern TOTAL = Pattern.compile("name=\"expectedTotal\" value=\"([^\"]+)\"");

	static final String VALID_CARD = "4111 1111 1111 1111";

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
	CartService carts;

	@Autowired
	AddressService addresses;

	@Autowired
	CouponRepository coupons;

	@Autowired
	CouponService couponService;

	@Autowired
	PaymentService payments;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	ApplicationContext context;

	Restaurant kitchen; // ₹30 delivery

	MenuItem thali; // ₹250: one thali costs 250 + 30 + 5 + 12.50 GST = ₹297.50

	@BeforeAll
	void createRestaurant() {
		kitchen = fixtures.restaurant("pay-kitchen", "Pay Kitchen").deliveryFee(30).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
		thali = items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false));
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	// ---------------------------------------------------------------- helpers

	/** A new customer's online order for one thali, awaiting payment; returns its code. */
	String pendingOrder(User customer) {
		return orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();
	}

	RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	MockHttpServletRequestBuilder payByUpi(User customer, String code, String upiId) {
		return post("/pay/" + code).with(as(customer)).with(csrf()).param("channel", "UPI").param("upiId", upiId);
	}

	MockHttpServletRequestBuilder payByCard(User customer, String code, String number) {
		return post("/pay/" + code).with(as(customer)).with(csrf()).param("channel", "CARD")
			.param("cardNumber", number).param("cardExpiry", "12/30").param("cardCvv", "123")
			.param("cardName", "Riya Shinde");
	}

	Map<String, Object> order(String code) {
		return jdbc.queryForMap("SELECT * FROM orders WHERE code = ?", code);
	}

	List<Map<String, Object>> attempts(String code) {
		return jdbc.queryForList("SELECT p.* FROM payments p JOIN orders o ON o.id = p.order_id WHERE o.code = ? "
				+ "ORDER BY p.id", code);
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return squash(mvc.perform(request).andExpect(status().isOk()).andExpect(view().name(PaymentController.VIEW))
			.andReturn().getResponse().getContentAsString());
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	static String find(Pattern pattern, String html) {
		Matcher m = pattern.matcher(html);
		assertThat(m.find()).as(pattern.pattern()).isTrue();
		return m.group(1);
	}

	/** A customer with an address and a thali in the cart checks out online; returns the order's code. */
	String checkOutOnline(User customer) throws Exception {
		AddressForm address = new AddressForm();
		address.setLabel(AddressLabel.HOME);
		address.setHouse("Flat 4B, Shree Apartments");
		address.setStreet("Rajarampuri 5th Lane");
		address.setPincode("416008");
		long addressId = addresses.create(customer.getId(), address).getId();
		if (carts.summary(new CartOwner.Customer(customer.getId())).count() == 0) {
			carts.setQuantity(new CartOwner.Customer(customer.getId()), thali.getId(), 1, false);
		}
		String checkout = mvc.perform(get("/checkout").with(as(customer))).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();
		String redirect = mvc.perform(post("/checkout").with(as(customer)).with(csrf())
			.param("idempotencyKey", find(KEY, checkout)).param("expectedTotal", find(TOTAL, checkout))
			.param("paymentMethod", "ONLINE").param("addressId", String.valueOf(addressId)))
			.andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
		assertThat(redirect).startsWith("/pay/ZK-26-");
		return redirect.substring("/pay/".length());
	}

	// ---------------------------------------------------------------- the page

	@Test
	void anOnlineCheckoutLandsOnTheTestModePaymentPage() throws Exception {
		User me = orders.customer();
		String code = checkOutOnline(me);

		String html = html(get("/pay/" + code).with(as(me)));

		assertThat(html).contains("<title>Pay for your order | Zaika</title>")
			.contains("TEST MODE - no real money").contains("No bank is contacted and nothing is charged.")
			.contains("Do not enter real card details.")
			.contains("Order <span>" + code + "</span> · <span>Pay Kitchen</span>")
			.contains("Pay by <strong>7:45 PM</strong> to keep this order.")
			.containsPattern("<a class=\"tab\" href=\"/pay/" + code + "\\?tab=upi\" aria-current=\"page\">UPI</a>")
			.contains("<a class=\"tab\" href=\"/pay/" + code + "?tab=card\">Card</a>")
			.contains("name=\"channel\" value=\"UPI\"").contains("name=\"upiId\"").contains("autocomplete=\"off\"")
			.contains("riya@upi").doesNotContain("name=\"cardNumber\"")
			.contains("Pay now").contains("₹297.50").contains("Simulate failure")
			.contains("1 ×</span> <span>Mutton Thali")
			.contains("Pay cash on delivery instead").contains("Cancel order").contains("Yes, cancel order")
			.contains("action=\"/pay/" + code + "/cash\"").contains("action=\"/pay/" + code + "/cancel\"")
			.contains("name=\"_csrf\"").doesNotContain("did not go through").doesNotContain("autocomplete=\"cc");
	}

	@Test
	void theCardTabAsksForTheCardWithoutBrowserAutofill() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);

		String html = html(get("/pay/" + code + "?tab=card").with(as(me)));

		assertThat(html).contains("<a class=\"tab\" href=\"/pay/" + code + "?tab=card\" aria-current=\"page\">Card</a>")
			.contains("name=\"channel\" value=\"CARD\"")
			.contains("Card number").contains("In test mode use 4111 1111 1111 1111.")
			.contains("Expiry (MM/YY)").contains("type=\"password\" id=\"cardCvv\"").contains("Name on card")
			.contains("inputmode=\"numeric\"").doesNotContain("name=\"upiId\"").doesNotContain("autocomplete=\"cc");
	}

	@Test
	void onlyTheOrdersCustomerMayUseIt() throws Exception {
		User me = orders.customer();
		User other = orders.customer();
		String code = pendingOrder(me);

		mvc.perform(get("/pay/" + code).with(anonymous())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/login"));
		mvc.perform(post("/pay/" + code + "/cancel").with(anonymous()).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/login"));
		User owner = kitchen.getOwner();
		mvc.perform(get("/pay/" + code).with(as(owner))).andExpect(status().isForbidden());

		mvc.perform(get("/pay/" + code).with(as(other))).andExpect(status().isNotFound());
		mvc.perform(payByUpi(other, code, "riya@upi")).andExpect(status().isNotFound());
		mvc.perform(post("/pay/" + code + "/cash").with(as(other)).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/pay/" + code + "/cancel").with(as(other)).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(get("/pay/ZK-26-999999").with(as(me))).andExpect(status().isNotFound());

		assertThat(order(code)).containsEntry("status", "PENDING_PAYMENT").containsEntry("payment_method", "ONLINE");
		assertThat(attempts(code)).isEmpty();
	}

	@Test
	void everyPostNeedsTheCsrfToken() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);

		mvc.perform(post("/pay/" + code).with(as(me)).param("channel", "UPI").param("upiId", "riya@upi"))
			.andExpect(status().isForbidden());
		mvc.perform(post("/pay/" + code + "/cancel").with(as(me))).andExpect(status().isForbidden());

		assertThat(order(code)).containsEntry("status", "PENDING_PAYMENT");
		assertThat(attempts(code)).isEmpty();
	}

	// ---------------------------------------------------------------- paying

	@Test
	void payingByUpiSendsTheOrderToTheKitchenAsPaid() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);
		clock.advance(Duration.ofMinutes(3));

		mvc.perform(payByUpi(me, code, "riya.shinde@upi")).andExpect(status().isFound())
			.andExpect(redirectedUrl("/orders/" + code))
			.andExpect(flash().attribute("flashSuccess",
					"Payment of ₹297.50 received. Pay Kitchen will confirm your order shortly."));

		assertThat(order(code)).containsEntry("status", "PLACED").containsEntry("payment_status", "PAID")
			.containsEntry("payment_method", "ONLINE")
			.containsEntry("placed_at", java.sql.Timestamp.valueOf(MutableClock.DEFAULT_NOW.plusMinutes(3)));
		List<Map<String, Object>> attempts = attempts(code);
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0)).containsEntry("gateway", "MOCK").containsEntry("channel", "UPI")
			.containsEntry("status", "SUCCEEDED").containsEntry("instrument", "ri••@upi")
			.containsEntry("failure_reason", null);
		assertThat((BigDecimal) attempts.get(0).get("amount")).isEqualByComparingTo("297.50");
		assertThat((String) attempts.get(0).get("gateway_reference")).matches("MOCK-[0-9A-F]{16}");
	}

	@Test
	void payingByCardKeepsOnlyTheLastFourDigits() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);

		mvc.perform(payByCard(me, code, VALID_CARD)).andExpect(redirectedUrl("/orders/" + code));

		Map<String, Object> attempt = attempts(code).get(0);
		assertThat(attempt).containsEntry("channel", "CARD").containsEntry("instrument", "Card ending 1111")
			.containsEntry("status", "SUCCEEDED");
		assertThat(attempt.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("41111111")
			.doesNotContain("Riya Shinde"));
		assertThat(order(code)).containsEntry("status", "PLACED").containsEntry("payment_status", "PAID");
	}

	@Test
	void invalidDetailsComeBackWithAMessageOnEachField() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);

		String upi = html(payByUpi(me, code, "riya"));
		assertThat(upi).contains("Check the details marked below.").contains("Enter a UPI ID like name@bank.")
			.contains("value=\"riya\"").contains("aria-invalid=\"true\"").contains("aria-current=\"page\">UPI</a>");

		String card = html(post("/pay/" + code).with(as(me)).with(csrf()).param("channel", "CARD")
			.param("cardNumber", "4111 1111 1111 1112").param("cardExpiry", "09/26").param("cardCvv", "987")
			.param("cardName", ""));
		assertThat(card).contains("aria-current=\"page\">Card</a>")
			.contains("That card number does not look right. Check the digits.")
			.contains("This card has expired. Use another card or pay by UPI.")
			.contains("Enter the name on the card.")
			.contains("value=\"4111 1111 1111 1112\"").contains("value=\"09/26\"").doesNotContain("987");

		assertThat(order(code)).containsEntry("status", "PENDING_PAYMENT").containsEntry("payment_status", "PENDING");
		assertThat(attempts(code)).isEmpty();
	}

	@Test
	void aDeclinedPaymentExplainsWhyAndCanBeRetried() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);

		mvc.perform(payByCard(me, code, VALID_CARD).param("simulateFailure", "true")).andExpect(status().isFound())
			.andExpect(redirectedUrl("/pay/" + code + "?tab=card"));

		assertThat(order(code)).containsEntry("status", "PENDING_PAYMENT").containsEntry("payment_status", "FAILED");
		assertThat(attempts(code)).singleElement().satisfies(a -> assertThat(a).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", MockPaymentGateway.CARD_DECLINED).containsEntry("gateway_reference", null));

		String page = html(get("/pay/" + code + "?tab=card").with(as(me)));
		assertThat(page).contains("role=\"alert\"").contains("Your payment of ₹297.50 did not go through.")
			.contains(MockPaymentGateway.CARD_DECLINED)
			.contains("Try again, pay in cash when your food arrives, or cancel the order.")
			.contains("Pay now").contains("Pay cash on delivery instead").contains("Yes, cancel order");

		mvc.perform(payByUpi(me, code, "riya@upi")).andExpect(redirectedUrl("/orders/" + code));

		assertThat(order(code)).containsEntry("status", "PLACED").containsEntry("payment_status", "PAID");
		assertThat(attempts(code)).extracting(a -> a.get("status")).containsExactly("FAILED", "SUCCEEDED");
	}

	@Test
	void afterADeclineTheCustomerCanPayCashOnDeliveryInstead() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);
		mvc.perform(payByUpi(me, code, "riya@upi").param("simulateFailure", "true"))
			.andExpect(redirectedUrl("/pay/" + code + "?tab=upi"));

		mvc.perform(post("/pay/" + code + "/cash").with(as(me)).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/orders/" + code)).andExpect(flash().attribute("flashSuccess",
					"Order placed. Pay in cash when your food arrives. The kitchen will confirm it shortly."));

		assertThat(order(code)).containsEntry("status", "PLACED").containsEntry("payment_method", "COD")
			.containsEntry("payment_status", "PENDING");
		// nothing left to pay here: the page sends the customer on to the order
		mvc.perform(get("/pay/" + code).with(as(me))).andExpect(redirectedUrl("/orders/" + code));
		mvc.perform(post("/pay/" + code + "/cash").with(as(me)).with(csrf())).andExpect(redirectedUrl("/pay/" + code));
	}

	@Test
	void aPaidOrderIsNeverChargedTwice() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);
		mvc.perform(payByUpi(me, code, "riya@upi")).andExpect(redirectedUrl("/orders/" + code));

		mvc.perform(get("/pay/" + code).with(as(me))).andExpect(redirectedUrl("/orders/" + code));
		mvc.perform(payByUpi(me, code, "riya@upi")).andExpect(redirectedUrl("/pay/" + code));
		mvc.perform(post("/pay/" + code + "/cancel").with(as(me)).with(csrf())).andExpect(redirectedUrl("/pay/" + code))
			.andExpect(flash().attributeCount(0));

		assertThat(attempts(code)).hasSize(1);
		assertThat(order(code)).containsEntry("status", "PLACED").containsEntry("payment_status", "PAID");
	}

	@Test
	void twoPaySubmitsAtOnceChargeOnce() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Callable<String> submit = () -> {
					start.await();
					return mvc.perform(payByUpi(me, code, "riya@upi")).andReturn().getResponse().getRedirectedUrl();
				};
				results.add(pool.submit(submit));
			}
			start.countDown();
			List<String> redirects = new ArrayList<>();
			for (Future<String> result : results) {
				redirects.add(result.get());
			}
			assertThat(redirects).containsExactlyInAnyOrder("/orders/" + code, "/pay/" + code);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(attempts(code)).hasSize(1);
		assertThat(order(code)).containsEntry("status", "PLACED").containsEntry("payment_status", "PAID");
	}

	@Test
	void anUnpaidCouponOrderShowsItsCouponAndDiscountOnThePaymentPage() throws Exception {
		coupons.save(new Coupon("PAYSHOW25", "₹25 off.", CouponType.FLAT, new BigDecimal("25"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = orders.customer();
		carts.setQuantity(new CartOwner.Customer(me.getId()), thali.getId(), 1, false);
		assertThat(couponService.apply(me.getId(), "PAYSHOW25").applied()).isTrue();
		String code = checkOutOnline(me);

		assertThat(html(get("/pay/" + code).with(as(me))))
			.contains("<div class=\"bill-row bill-discount\"><dt>Coupon PAYSHOW25</dt><dd>−₹25.00</dd></div>");
	}

	// ---------------------------------------------------------------- cancelling and expiry

	@Test
	void cancellingAnUnpaidOrderGivesTheCouponBack() throws Exception {
		Coupon coupon = coupons.save(new Coupon("PAYBACK40", "₹40 off.", CouponType.FLAT, new BigDecimal("40"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = orders.customer();
		carts.setQuantity(new CartOwner.Customer(me.getId()), thali.getId(), 1, false);
		assertThat(couponService.apply(me.getId(), "PAYBACK40").applied()).isTrue();
		String code = checkOutOnline(me);
		long orderId = ((Number) order(code).get("id")).longValue();
		assertThat(jdbc.queryForList("SELECT order_id FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).containsExactly(orderId);

		mvc.perform(post("/pay/" + code + "/cancel").with(as(me)).with(csrf())).andExpect(status().isFound())
			.andExpect(redirectedUrl("/pay/" + code))
			.andExpect(flash().attribute("flashInfo", "Order cancelled. You have not been charged."));

		assertThat(order(code)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "CUSTOMER")
			.containsEntry("cancel_reason", "Cancelled before paying").containsEntry("payment_status", "PENDING");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isZero();

		String page = html(get("/pay/" + code).with(as(me)));
		assertThat(page).contains("Order cancelled").contains("You cancelled <strong>" + code + "</strong> before paying.")
			.contains("Order again from Pay Kitchen").contains("href=\"/r/pay-kitchen\"").contains("Browse restaurants")
			.doesNotContain("Pay now").doesNotContain("TEST MODE");

		mvc.perform(payByUpi(me, code, "riya@upi")).andExpect(redirectedUrl("/pay/" + code));
		mvc.perform(post("/pay/" + code + "/cash").with(as(me)).with(csrf())).andExpect(redirectedUrl("/pay/" + code));
		assertThat(attempts(code)).isEmpty();
		assertThat(order(code)).containsEntry("status", "CANCELLED").containsEntry("payment_method", "ONLINE");
	}

	@Test
	void unpaidOrdersExpireFifteenMinutesAfterCheckout() throws Exception {
		Coupon coupon = coupons.save(new Coupon("EXPIRE30", "₹30 off.", CouponType.FLAT, new BigDecimal("30"),
				MutableClock.DEFAULT_NOW.minusDays(1)));
		User me = orders.customer();
		String first = pendingOrder(me); // 19:30
		long firstId = ((Number) order(first).get("id")).longValue();
		jdbc.update("INSERT INTO coupon_redemptions (coupon_id, user_id, discount, order_id, created_at, updated_at, "
				+ "version) VALUES (?, ?, 30.00, ?, ?, ?, 0)", coupon.getId(), me.getId(), firstId,
				MutableClock.DEFAULT_NOW, MutableClock.DEFAULT_NOW);
		String paid = pendingOrder(me);
		mvc.perform(payByUpi(me, paid, "riya@upi")).andExpect(redirectedUrl("/orders/" + paid));
		String cash = orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED).getCode();
		clock.advance(Duration.ofMinutes(5));
		String second = pendingOrder(me); // 19:35

		clock.setTo(MutableClock.DEFAULT_NOW.plusMinutes(15).minusSeconds(1));
		assertThat(payments.expireUnpaid()).isZero();
		assertThat(order(first)).containsEntry("status", "PENDING_PAYMENT");

		clock.setTo(MutableClock.DEFAULT_NOW.plusMinutes(15));
		assertThat(payments.expireUnpaid()).isPositive();

		assertThat(order(first)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "SYSTEM")
			.containsEntry("cancel_reason", "Not paid within 15 minutes")
			.containsEntry("cancelled_at", java.sql.Timestamp.valueOf(MutableClock.DEFAULT_NOW.plusMinutes(15)));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE order_id = ?", Long.class,
				firstId)).isZero();
		assertThat(order(second)).containsEntry("status", "PENDING_PAYMENT");
		assertThat(order(paid)).containsEntry("status", "PLACED");
		assertThat(order(cash)).containsEntry("status", "PLACED");

		String page = html(get("/pay/" + first).with(as(me)));
		assertThat(page).contains("Time ran out for this order")
			.contains("because it was not paid within 15 minutes. You have not been charged.")
			.contains("Order again from Pay Kitchen").doesNotContain("Pay now");

		clock.setTo(MutableClock.DEFAULT_NOW.plusMinutes(20));
		payments.expireUnpaid();
		assertThat(order(second)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "SYSTEM");
		assertThat(payments.expireUnpaid()).isZero(); // nothing left to do
	}

	@Test
	void aLatePaymentFindsTheOrderAlreadyExpired() throws Exception {
		User me = orders.customer();
		String code = pendingOrder(me);
		clock.advance(Duration.ofMinutes(16)); // the job has not run yet

		mvc.perform(payByUpi(me, code, "riya@upi")).andExpect(status().isFound())
			.andExpect(redirectedUrl("/pay/" + code));

		assertThat(attempts(code)).isEmpty();
		assertThat(order(code)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "SYSTEM")
			.containsEntry("payment_status", "PENDING");
		assertThat(html(get("/pay/" + code).with(as(me)))).contains("Time ran out for this order");
	}

	@Test
	void theDeadlineIsShownAndOpeningThePageAfterItExpiresTheOrder() throws Exception {
		User me = orders.customer();
		clock.setTo(LocalDateTime.of(2026, 10, 4, 21, 50));
		String code = pendingOrder(me);

		assertThat(html(get("/pay/" + code).with(as(me)))).contains("Pay by <strong>10:05 PM</strong>");

		clock.setTo(LocalDateTime.of(2026, 10, 4, 22, 5));
		assertThat(html(get("/pay/" + code).with(as(me)))).contains("Time ran out for this order");
		assertThat(order(code)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "SYSTEM");
	}

	@Test
	void theExpiryJobIsOffInTests() {
		// the test profile sets app.payments.expiry-cron to "-"; DevProfileTest proves it runs every minute elsewhere
		assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
			.flatMap(ScheduledTaskHolder::getScheduledTasks)
			.noneMatch(task -> task.toString().contains("PaymentService.expireUnpaid"));
	}

}
