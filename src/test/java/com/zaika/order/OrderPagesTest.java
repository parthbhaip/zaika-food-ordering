package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.cart.PricingService;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.AddressForm;
import com.zaika.user.AddressLabel;
import com.zaika.user.AddressService;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * The customer's orders: the confirmation after checkout and payment, the paginated history with status chips and
 * totals, and one order's page (dishes, bill, address, payment, how it ended). Another customer's order is a 404.
 * Own database; the test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPagesTest {

	static final Pattern KEY = Pattern.compile("name=\"idempotencyKey\" value=\"([^\"]+)\"");

	static final Pattern TOTAL = Pattern.compile("name=\"expectedTotal\" value=\"([^\"]+)\"");

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
	PricingService pricing;

	@Autowired
	UserRepository users;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	CartService carts;

	@Autowired
	AddressService addresses;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	MutableClock clock;

	Restaurant kitchen; // ₹30 delivery

	MenuItem thali; // ₹250, non-veg: one thali costs 250 + 30 + 5 + 12.50 GST = ₹297.50

	MenuItem lassi; // ₹60, veg

	@BeforeAll
	void createRestaurant() {
		kitchen = fixtures.restaurant("order-kitchen", "Order Kitchen").deliveryFee(30).save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
		thali = items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false));
		lassi = items.save(new MenuItem(menu, "Sweet Lassi", new BigDecimal("60"), true));
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

	static String find(Pattern pattern, String html) {
		Matcher m = pattern.matcher(html);
		assertThat(m.find()).as(pattern.pattern()).isTrue();
		return m.group(1);
	}

	/** Checks out one thali and two lassis with notes; returns the redirect (with its flash attributes). */
	MvcResult checkOut(User customer, String method) throws Exception {
		AddressForm address = new AddressForm();
		address.setLabel(AddressLabel.WORK);
		address.setHouse("Office 12, Sai Plaza");
		address.setStreet("Station Road, Shahupuri");
		address.setPincode("416001");
		long addressId = addresses.create(customer.getId(), address).getId();
		CartOwner owner = new CartOwner.Customer(customer.getId());
		carts.setQuantity(owner, thali.getId(), 1, false);
		carts.setQuantity(owner, lassi.getId(), 2, false);
		carts.saveInstructions(owner, "Less spicy, please");
		String checkout = html(get("/checkout").with(as(customer)));
		return mvc.perform(post("/checkout").with(as(customer)).with(csrf())
			.param("idempotencyKey", find(KEY, checkout)).param("expectedTotal", find(TOTAL, checkout))
			.param("paymentMethod", method).param("addressId", String.valueOf(addressId))
			.param("deliveryNote", "Ring the bell twice"))
			.andExpect(status().isFound()).andReturn();
	}

	/** Follows a redirect, carrying its flash attributes as the browser's session would. */
	String follow(User customer, MvcResult redirect) throws Exception {
		return html(get(redirect.getResponse().getRedirectedUrl()).with(as(customer))
			.flashAttrs(redirect.getFlashMap()));
	}

	// ---------------------------------------------------------------- confirmation

	@Test
	void aCashCheckoutLandsOnTheConfirmationWithItsToast() throws Exception {
		User me = orders.customer();
		MvcResult placed = checkOut(me, "COD");
		String url = placed.getResponse().getRedirectedUrl();
		assertThat(url).startsWith("/orders/ZK-26-");
		String code = url.substring("/orders/".length());

		String html = follow(me, placed);

		assertThat(html).contains("<title>Order " + code + " | Zaika</title>")
			.contains("Order placed. Order Kitchen will confirm it shortly.") // the toast
			.contains("id=\"order-confirmed-title\">Order placed</h2>")
			.contains("<strong>Order Kitchen</strong> has your order and will confirm it shortly.")
			.contains("Arriving by <strong>8:00 PM</strong>").contains("(in 30 min)")
			.contains("Keep ₹423.50 ready to pay in cash at the door.");
		// a later visit is the plain order page
		assertThat(html(get(url).with(as(me)))).doesNotContain("order-confirmed").doesNotContain("has your order");
	}

	@Test
	void aPaidOnlineOrderLandsOnTheConfirmationWithThePaymentShown() throws Exception {
		User me = orders.customer();
		MvcResult checkout = checkOut(me, "ONLINE");
		String code = checkout.getResponse().getRedirectedUrl().substring("/pay/".length());
		MvcResult paid = mvc.perform(post("/pay/" + code).with(as(me)).with(csrf()).param("channel", "UPI")
			.param("upiId", "riya@upi")).andExpect(redirectedUrl("/orders/" + code)).andReturn();

		String html = follow(me, paid);

		assertThat(html).contains("Payment of ₹423.50 received.").contains("You paid ₹423.50 online.")
			.contains("<dt>Method</dt><dd>Online (test mode)</dd>").contains("<dt>Status</dt><dd>Paid</dd>")
			.contains("<dt>Paid with</dt><dd>ri••@upi</dd>").containsPattern("MOCK-[0-9A-F]{16}")
			.contains("<dt>Paid</dt><dd>₹423.50</dd>");
	}

	@Test
	void switchingToCashLandsOnTheConfirmation() throws Exception {
		User me = orders.customer();
		String code = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();
		MvcResult cash = mvc.perform(post("/pay/" + code + "/cash").with(as(me)).with(csrf()))
			.andExpect(redirectedUrl("/orders/" + code)).andReturn();

		assertThat(follow(me, cash)).contains("id=\"order-confirmed-title\">Order placed</h2>")
			.contains("Keep ₹297.50 ready to pay in cash at the door.");
	}

	// ---------------------------------------------------------------- the order page

	@Test
	void theOrderPageShowsDishesBillAddressAndPayment() throws Exception {
		User me = orders.customer();
		String code = checkOut(me, "COD").getResponse().getRedirectedUrl().substring("/orders/".length());

		String html = squash(mvc.perform(get("/orders/" + code).with(as(me))).andExpect(status().isOk())
			.andExpect(view().name(OrderController.DETAIL_VIEW)).andReturn().getResponse().getContentAsString());

		assertThat(html).contains("<h1 class=\"order-title\">Order " + code + "</h1>")
			.contains("class=\"badge order-status badge-primary\">Placed</span>")
			.contains("<a href=\"/r/order-kitchen\">Order Kitchen</a>").contains("4 Oct 2026, 7:30 PM")
			.contains("<strong>Placed.</strong> Arriving by <strong>8:00 PM</strong>")
			.contains("3 items")
			.contains("1 ×</span> <span>Mutton Thali</span>").contains("₹250.00")
			.contains("2 ×</span> <span>Sweet Lassi</span>").contains("₹60.00 each").contains("₹120.00")
			.contains("<span class=\"visually-hidden\">Vegetarian</span>")
			.contains("<span class=\"visually-hidden\">Non-vegetarian</span>")
			.contains("Note for the restaurant:</strong> <span>Less spicy, please</span>")
			// 370 items + 30 delivery + 5 platform + 18.50 GST
			.contains("<dt>Item total</dt><dd>₹370.00</dd>").contains("<dt>Delivery fee</dt> <dd>₹30.00</dd>")
			.contains("<dt>Platform fee</dt><dd>₹5.00</dd>").contains("<dt>GST on food</dt><dd>₹18.50</dd>")
			.contains("<dt>Total</dt><dd>₹423.50</dd>").doesNotContain("bill-discount")
			.contains("<strong>Work</strong>")
			.contains("Office 12, Sai Plaza, Station Road, Shahupuri, Kolhapur 416001")
			.contains("Note for the rider:</strong> <span>Ring the bell twice</span>")
			.contains("<dt>Method</dt><dd>Cash on delivery</dd>").contains("<dt>Status</dt><dd>Pending</dd>")
			.doesNotContain("Paid with").doesNotContain("Order again")
			.contains("href=\"/orders\"").contains("All orders").contains("Help with this order");
	}

	@Test
	void aCouponOrderShowsItsDiscountAndAFreeDelivery() throws Exception {
		User me = orders.customer();
		String code = tx.execute(status -> {
			MenuItem dish = items.findWithRestaurantById(thali.getId()).orElseThrow();
			var bill = pricing.price(new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("75.00"));
			Order order = new Order(users.findById(me.getId()).orElseThrow(), dish.getRestaurant(),
					OrderFixtures.ADDRESS, PaymentMethod.COD, bill, "FEAST75", LocalDateTime.now(clock).plusMinutes(30));
			order.addItem(dish, 2);
			order = orderRepository.save(order);
			workflow.transition(order, OrderStatus.PLACED, OrderActor.system());
			return order.getCode();
		});

		assertThat(html(get("/orders/" + code).with(as(me))))
			.contains("<dt>Coupon FEAST75</dt><dd>−₹75.00</dd>").contains("<span class=\"bill-free\">FREE</span>");
	}

	@Test
	void anOrderAwaitingPaymentOffersToFinishIt() throws Exception {
		User me = orders.customer();
		String code = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();

		assertThat(html(get("/orders/" + code).with(as(me))))
			.contains("class=\"badge order-status badge-warning\">Awaiting payment</span>")
			.contains("Waiting for your payment.").contains("Pay by <strong>7:45 PM</strong>")
			.contains("href=\"/pay/" + code + "\">Complete payment</a>")
			.contains("<dt>Method</dt><dd>Online (test mode)</dd>").doesNotContain("order-confirmed");
	}

	@Test
	void aDeliveredOrderSaysWhenAndOffersToOrderAgain() throws Exception {
		User me = orders.customer();
		String code = orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED).getCode();

		assertThat(html(get("/orders/" + code).with(as(me))))
			.contains("class=\"badge order-status badge-success\">Delivered</span>")
			.contains("Delivered on <strong>4 Oct 2026, 7:30 PM</strong>")
			.contains("<dt>Status</dt><dd>Paid</dd>").contains("<dt>Paid</dt><dd>₹297.50</dd>")
			.contains("href=\"/r/order-kitchen\">Order again from Order Kitchen</a>")
			.doesNotContain("Arriving by");
	}

	@Test
	void aCancelledOrderShowsWhoAndWhy() throws Exception {
		User me = orders.customer();
		String code = orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.CANCELLED).getCode();

		assertThat(html(get("/orders/" + code).with(as(me))))
			.contains("class=\"badge order-status badge-danger\">Cancelled</span>")
			.contains("<strong>You cancelled this order.</strong>").contains("Reason: Ordered by mistake.")
			.contains("You have not been charged.").contains("Order again from Order Kitchen");
	}

	@Test
	void aRejectedPaidOrderShowsTheRefund() throws Exception {
		User me = orders.customer();
		String code = orders.create(me.getId(), thali.getId(), 1, PaymentMethod.ONLINE).getCode();
		tx.executeWithoutResult(status -> {
			Order order = orderRepository.findForUpdateByCode(code).orElseThrow();
			workflow.confirmPayment(order);
			workflow.transition(order, OrderStatus.REJECTED,
					OrderActor.restaurant(kitchen.getOwner().getId()), "Out of mutton tonight");
		});

		assertThat(html(get("/orders/" + code).with(as(me))))
			.contains("class=\"badge order-status badge-danger\">Rejected</span>")
			.contains("<strong>Order Kitchen could not take this order.</strong>")
			.contains("Reason: Out of mutton tonight.")
			.contains("Your refund of ₹297.50 is on its way. It reaches your account in 5-7 working days.")
			.contains("<dt>Status</dt><dd>Refund in progress</dd>").doesNotContain("You have not been charged.");
	}

	@Test
	void aKitchenThatLeftZaikaIsNotLinked() throws Exception {
		Restaurant gone = fixtures.restaurant("gone-kitchen", "Gone Kitchen").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(gone.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Poha", new BigDecimal("40"), true));
		User me = orders.customer();
		String code = orders.place(me.getId(), dish.getId(), 2, PaymentMethod.COD, OrderStatus.DELIVERED).getCode();
		tx.executeWithoutResult(status -> restaurants.findById(gone.getId()).orElseThrow()
			.changeStatus(RestaurantStatus.SUSPENDED, "Licence expired"));

		assertThat(html(get("/orders/" + code).with(as(me)))).contains("<span>Gone Kitchen</span>")
			.doesNotContain("href=\"/r/gone-kitchen\"").doesNotContain("Order again");
	}

	@Test
	void onlyTheOrdersCustomerSeesIt() throws Exception {
		User me = orders.customer();
		User other = orders.customer();
		String code = orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED).getCode();

		mvc.perform(get("/orders/" + code).with(as(other))).andExpect(status().isNotFound());
		mvc.perform(get("/orders/ZK-26-999999").with(as(me))).andExpect(status().isNotFound());
		mvc.perform(get("/orders/" + code).with(anonymous())).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/orders").with(anonymous())).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/orders/" + code).with(as(kitchen.getOwner()))).andExpect(status().isForbidden());
		mvc.perform(get("/orders").with(as(kitchen.getOwner()))).andExpect(status().isForbidden());
		assertThat(html(get("/orders").with(as(other)))).doesNotContain(code);
	}

	// ---------------------------------------------------------------- the history

	@Test
	void noOrdersYetShowsAnEmptyState() throws Exception {
		User me = orders.customer();

		String html = squash(mvc.perform(get("/orders").with(as(me))).andExpect(status().isOk())
			.andExpect(view().name(OrderController.LIST_VIEW)).andReturn().getResponse().getContentAsString());

		assertThat(html).contains("<title>Your orders | Zaika</title>").contains("No orders yet")
			.contains("href=\"/restaurants\">Browse restaurants</a>").doesNotContain("order-card")
			.doesNotContain("class=\"pagination\"");
	}

	@Test
	void theHistoryListsOrdersNewestFirstWithChipsAndTotals() throws Exception {
		User me = orders.customer();
		String delivered = orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED)
			.getCode();
		clock.advance(java.time.Duration.ofMinutes(5));
		String unpaid = orders.create(me.getId(), lassi.getId(), 3, PaymentMethod.ONLINE).getCode();

		String html = html(get("/orders").with(as(me)));

		assertThat(html).contains("2 orders so far");
		assertThat(html.indexOf(unpaid)).isLessThan(html.indexOf(delivered));
		String first = html.substring(html.indexOf(unpaid), html.indexOf(delivered));
		assertThat(first).contains("4 Oct 2026, 7:35 PM").contains("badge badge-warning\">Awaiting payment</span>")
			.contains("3 × Sweet Lassi").contains("Online · Pending")
			.contains("href=\"/pay/" + unpaid + "\">Pay now</a>")
			.contains("href=\"/orders/" + unpaid + "\">View order");
		String second = html.substring(html.indexOf(delivered));
		assertThat(second).contains("badge badge-success\">Delivered</span>").contains("1 × Mutton Thali")
			.contains("₹297.50").contains("Cash on delivery").contains("href=\"/orders/" + delivered + "\">View order")
			.doesNotContain("Pay now");
	}

	@Test
	void theHistoryIsPaginatedTenToAPage() throws Exception {
		User me = orders.customer();
		String[] codes = new String[12];
		for (int i = 0; i < codes.length; i++) {
			codes[i] = orders.place(me.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED).getCode();
			clock.advance(java.time.Duration.ofMinutes(1));
		}

		String first = html(get("/orders").with(as(me)));
		assertThat(first).contains("12 orders so far").contains(codes[11]).contains(codes[2])
			.doesNotContain(codes[1] + "<").doesNotContain(codes[0] + "<")
			.contains("class=\"pagination\"").contains("href=\"/orders?page=2\"");
		String second = html(get("/orders?page=2").with(as(me)));
		assertThat(second).contains(codes[1]).contains(codes[0]).doesNotContain(codes[2] + "<")
			.contains("href=\"/orders\"");

		mvc.perform(get("/orders?page=1").with(as(me))).andExpect(redirectedUrl("/orders"));
		mvc.perform(get("/orders?page=0").with(as(me))).andExpect(redirectedUrl("/orders"));
		mvc.perform(get("/orders?page=abc").with(as(me))).andExpect(redirectedUrl("/orders"));
		mvc.perform(get("/orders?page=9").with(as(me))).andExpect(redirectedUrl("/orders?page=2"));
		mvc.perform(get("/orders?page=2").with(as(orders.customer()))).andExpect(redirectedUrl("/orders"));
	}

	// ---------------------------------------------------------------- queries

	@Test
	void pagesRunAFixedNumberOfQueriesWhateverTheyHold() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		User few = orders.customer();
		String small = orders.place(few.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED).getCode();
		User many = orders.customer(); // 11 orders: a full first page and a second one
		String big = null;
		for (int i = 0; i < 11; i++) {
			big = orders.place(many.getId(), lassi.getId(), 2, PaymentMethod.COD, OrderStatus.DELIVERED).getCode();
		}
		RequestPostProcessor asFew = as(few); // the account lookup is not part of the page
		RequestPostProcessor asMany = as(many);
		html(get("/orders").with(as(few))); // warm up

		stats.clear();
		html(get("/orders").with(asFew));
		long listFew = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/orders").with(asMany));
		long listMany = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/orders/" + small).with(asFew));
		long detailCash = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/orders/" + big).with(asMany));
		long detailBig = stats.getPrepareStatementCount();

		// cart count, the page of orders with restaurants, their dishes, and the total when the page is full
		assertThat(listFew).isEqualTo(3);
		assertThat(listMany).isEqualTo(4);
		// cart count, the order with its restaurant and dishes: nothing per dish
		assertThat(detailCash).isEqualTo(2);
		assertThat(detailBig).isEqualTo(2);
	}

}
