package com.zaika.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Coupons on the cart page and the restaurant page: the coupon box for guests and customers, applying and removing
 * as plain forms (PRG, the reason and the typed code kept after a failure) and through htmx (the coupon box and bill
 * swapped in place, toasts out of band), the bill with the discount, a coupon taken off when the cart no longer
 * qualifies, coming back to the cart after "Sign in to apply a coupon", and who may use coupons. Own database; the
 * test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:couponpage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponPageTest {

	static final String PASSWORD = "Demo@1234";

	static final LocalDateTime NOW = MutableClock.DEFAULT_NOW;

	static final AtomicInteger CODES = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	CartService carts;

	@Autowired
	CouponRepository coupons;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ZaikaUserDetailsService userDetails;

	Restaurant kitchen;

	Restaurant other;

	MenuItem thali; // ₹250

	MenuItem chai; // ₹40

	/** Open at the kitchen, ₹50 off from ₹200, the only coupon of {@link #other}. */
	Coupon otherKitchenOnly;

	@BeforeAll
	void createRestaurants() {
		kitchen = fixtures.restaurant("coupon-kitchen", "Coupon Kitchen").deliveryFee(35).save();
		other = fixtures.restaurant("other-kitchen", "Other Kitchen").save();
		thali = dish(kitchen, "Mutton Thali", "250");
		chai = dish(kitchen, "Cutting Chai", "40");
		otherKitchenOnly = coupon(CouponType.FLAT, "50", c -> {
			c.setRestaurant(other);
			c.setMinOrder(new BigDecimal("200"));
		});
	}

	MenuItem dish(Restaurant restaurant, String name, String price) {
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(restaurant.getId()).get(0);
		return items.save(new MenuItem(menu, name, new BigDecimal(price), false));
	}

	Coupon coupon(CouponType type, String value, Consumer<Coupon> setup) {
		Coupon coupon = new Coupon("PAGE" + CODES.incrementAndGet(), "A test offer for the page.", type,
				new BigDecimal(value), NOW.minusDays(1));
		setup.accept(coupon);
		return coupons.save(coupon);
	}

	User customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@coupon.zaika.test", "9876543210",
				passwordEncoder.encode(PASSWORD), Role.CUSTOMER));
	}

	User customerWith(MenuItem item, int quantity) {
		User user = customer();
		carts.setQuantity(new CartOwner.Customer(user.getId()), item.getId(), quantity, false);
		return user;
	}

	RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	String cartPage(User user) throws Exception {
		return squash(mvc.perform(get("/cart").with(as(user))).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString());
	}

	MvcResult htmxPost(String url, User user, String code) throws Exception {
		var request = post(url).header("HX-Request", "true").with(as(user)).with(csrf());
		if (code != null) {
			request.param("code", code);
		}
		return mvc.perform(request).andExpect(status().isOk()).andReturn();
	}

	static String body(MvcResult result) throws Exception {
		return squash(result.getResponse().getContentAsString()).strip();
	}

	// ---------------------------------------------------------------- without JavaScript

	@Test
	void aCustomerAppliesACodeAndTheBillShowsTheDiscount() throws Exception {
		Coupon coupon = coupon(CouponType.PERCENT, "20", c -> c.setMaxDiscount(new BigDecimal("120")));
		User riya = customerWith(thali, 2); // ₹500

		String before = cartPage(riya);
		assertThat(before).contains("Offers and coupons", "<label class=\"field-label\" for=\"coupon-code\">Coupon code</label>",
				"action=\"/cart/coupon\" method=\"post\"", "hx-post=\"/cart/coupon\" hx-target=\"#cart-side\"",
				"<span class=\"coupon-code\">" + coupon.getCode() + "</span> <strong>20% off up to ₹120</strong>",
				"aria-label=\"Apply " + coupon.getCode() + "\"")
			.doesNotContain("Sign in to apply a coupon");

		mvc.perform(post("/cart/coupon").param("code", coupon.getCode().toLowerCase()).with(as(riya)).with(csrf()))
			.andExpect(redirectedUrl("/cart"))
			.andExpect(flash().attribute("flashSuccess", coupon.getCode() + " applied. You save ₹100.00 on this order."));

		// ₹500 - ₹100 + free delivery (above ₹499) + ₹5 platform + 5% GST on ₹400 (₹20) = ₹425
		String after = cartPage(riya);
		assertThat(after).contains("<strong>" + coupon.getCode() + " applied</strong>",
				"You save ₹100.00 with 20% off up to ₹120", "aria-label=\"Remove coupon " + coupon.getCode() + "\"",
				"<dt>Coupon " + coupon.getCode() + "</dt><dd>−₹100.00</dd>", "<dd>₹20.00</dd>",
				"<dt>To pay</dt><dd>₹425.00</dd>", "<span class=\"badge badge-success\">Applied</span>")
			.doesNotContain("id=\"coupon-code\"");
	}

	@Test
	void aCodeThatDoesNotWorkSaysWhyAndKeepsWhatWasTyped() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "60", c -> c.setMinOrder(new BigDecimal("300")));
		User riya = customerWith(thali, 1); // ₹250

		MvcResult result = mvc.perform(post("/cart/coupon").param("code", " " + coupon.getCode() + " ").with(as(riya))
			.with(csrf()))
			.andExpect(redirectedUrl("/cart"))
			.andReturn();
		String message = "Add ₹50 more to use " + coupon.getCode() + ". It needs a minimum order of ₹300.";
		assertThat(result.getFlashMap().get("couponError")).isEqualTo(message);
		assertThat(result.getFlashMap().get("couponCode")).isEqualTo(coupon.getCode());

		String page = squash(mvc.perform(get("/cart").with(as(riya)).flashAttrs(result.getFlashMap()))
			.andReturn().getResponse().getContentAsString());
		assertThat(page).contains("class=\"field has-error\"", "value=\"" + coupon.getCode() + "\"",
				"aria-invalid=\"true\"", "aria-describedby=\"coupon-error\"", "id=\"coupon-error\" role=\"alert\"",
				message, "<dt>To pay</dt>")
			.doesNotContain("bill-discount");

		mvc.perform(post("/cart/coupon").param("code", "").with(as(riya)).with(csrf()))
			.andExpect(flash().attribute("couponError", "Enter a coupon code."));
	}

	@Test
	void anotherKitchensCouponIsListedOnlyThereAndRefusedHere() throws Exception {
		User riya = customerWith(thali, 1);
		assertThat(cartPage(riya)).doesNotContain(">" + otherKitchenOnly.getCode() + "<");

		mvc.perform(post("/cart/coupon").param("code", otherKitchenOnly.getCode()).with(as(riya)).with(csrf()))
			.andExpect(flash().attribute("couponError",
					otherKitchenOnly.getCode() + " works only on orders from Other Kitchen."));
	}

	@Test
	void removingTheCouponPutsTheBillBack() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "30", c -> {
		});
		User riya = customerWith(thali, 1);
		mvc.perform(post("/cart/coupon").param("code", coupon.getCode()).with(as(riya)).with(csrf()));

		mvc.perform(post("/cart/coupon/remove").with(as(riya)).with(csrf()))
			.andExpect(redirectedUrl("/cart"))
			.andExpect(flash().attribute("flashInfo", "Coupon removed."));

		assertThat(cartPage(riya)).contains("id=\"coupon-code\"").doesNotContain("bill-discount", "Remove coupon");
	}

	@Test
	void aCouponTheCartNoLongerQualifiesForIsTakenOffWithTheReason() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "40", c -> c.setMinOrder(new BigDecimal("280")));
		User riya = customerWith(thali, 1);
		carts.setQuantity(new CartOwner.Customer(riya.getId()), chai.getId(), 1, false); // ₹290
		mvc.perform(post("/cart/coupon").param("code", coupon.getCode()).with(as(riya)).with(csrf()));
		assertThat(cartPage(riya)).contains("<dt>Coupon " + coupon.getCode() + "</dt><dd>−₹40.00</dd>");

		// the cart page's "-" on the chai, through htmx: the bill comes back out of band without the coupon
		String answer = body(mvc.perform(post("/cart/items/" + chai.getId()).header("HX-Request", "true")
			.param("quantity", "0").param("from", "cart").with(as(riya)).with(csrf()))
			.andExpect(status().isOk()).andReturn());
		assertThat(answer).contains("id=\"cart-side\" class=\"cart-side\" hx-swap-oob=\"true\"",
				"We removed " + coupon.getCode() + ". Add ₹30 more to use " + coupon.getCode()
						+ ". It needs a minimum order of ₹280.")
			.doesNotContain("bill-discount");
		assertThat(cartPage(riya)).doesNotContain("We removed", "bill-discount");
	}

	// ---------------------------------------------------------------- htmx

	@Test
	void htmxAppliesInPlaceWithTheBillAndAToast() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "75", c -> c.setMinOrder(new BigDecimal("200")));
		User riya = customerWith(thali, 1); // ₹250

		String answer = body(htmxPost("/cart/coupon", riya, coupon.getCode()));

		assertThat(answer).startsWith("<div id=\"cart-side\" class=\"cart-side\">")
			.contains("<dt>Coupon " + coupon.getCode() + "</dt><dd>−₹75.00</dd>",
					"id=\"coupon-remove\" class=\"btn btn-ghost btn-sm\" aria-label=\"Remove coupon " + coupon.getCode()
							+ "\" autofocus",
					"hx-swap-oob=\"beforeend:#toasts\"", coupon.getCode() + " applied. You save ₹75.00 on this order.");
	}

	@Test
	void htmxShowsTheReasonUnderTheFieldAndKeepsFocusThere() throws Exception {
		User riya = customerWith(thali, 1);

		String answer = body(htmxPost("/cart/coupon", riya, "nosuch99"));

		assertThat(answer).startsWith("<div id=\"cart-side\" class=\"cart-side\">")
			.contains("value=\"nosuch99\"", "aria-invalid=\"true\"",
					"We could not find a coupon called NOSUCH99. Check the code and try again.")
			.containsPattern("id=\"coupon-code\"[^>]*autofocus")
			.doesNotContain("hx-swap-oob=\"beforeend:#toasts\"");
	}

	@Test
	void htmxRemovesInPlace() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "30", c -> {
		});
		User riya = customerWith(thali, 1);
		htmxPost("/cart/coupon", riya, coupon.getCode());

		String answer = body(htmxPost("/cart/coupon/remove", riya, null));

		assertThat(answer).startsWith("<div id=\"cart-side\" class=\"cart-side\">")
			.contains("Coupon removed.", "id=\"coupon-code\"")
			.containsPattern("id=\"coupon-code\"[^>]*autofocus")
			.doesNotContain("bill-discount");
	}

	@Test
	void htmxOnACartEmptiedInAnotherTabReloadsTheCart() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "30", c -> {
		});
		User riya = customer();

		MvcResult result = htmxPost("/cart/coupon", riya, coupon.getCode());

		assertThat(result.getResponse().getHeader("HX-Redirect")).isEqualTo("/cart");
	}

	// ---------------------------------------------------------------- guests, staff, other customers

	@Test
	void guestsSeeTheOffersAndSignInToApplyThenComeBackToTheirCart() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "25", c -> {
		});
		User riya = customer();
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "1").session(session).with(csrf()));

		String page = squash(mvc.perform(get("/cart").session(session)).andReturn().getResponse().getContentAsString());
		assertThat(page).contains("<span class=\"coupon-code\">" + coupon.getCode() + "</span>",
				"<a href=\"/cart/sign-in\">Sign in to apply a coupon</a>. Your cart comes with you.")
			.doesNotContain("id=\"coupon-code\"", "aria-label=\"Apply " + coupon.getCode() + "\"");

		mvc.perform(get("/cart/sign-in").session(session)).andExpect(redirectedUrl("/login"));
		MvcResult login = mvc.perform(post("/login").param("email", riya.getEmail()).param("password", PASSWORD)
			.session(session).with(csrf())).andReturn();
		String back = login.getResponse().getRedirectedUrl();
		assertThat(back).startsWith("http://localhost/cart/sign-in");
		mvc.perform(get(java.net.URI.create(back)).session(session)).andExpect(redirectedUrl("/cart"));

		String signedIn = squash(mvc.perform(get("/cart").session(session)).andReturn().getResponse()
			.getContentAsString());
		assertThat(signedIn).contains("Mutton Thali", "id=\"coupon-code\"", "aria-label=\"Apply " + coupon.getCode() + "\"");
	}

	@Test
	void guestPostsAreSentToSignIn() throws Exception {
		mvc.perform(post("/cart/coupon").param("code", "ANYCODE").with(csrf()))
			.andExpect(redirectedUrl("/cart/sign-in"));
		mvc.perform(post("/cart/coupon/remove").header("HX-Request", "true").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(header().string("HX-Redirect", "/cart/sign-in"));
	}

	@Test
	void staffHaveNoCouponsAndCsrfIsRequired() throws Exception {
		User owner = users.findByEmailIgnoreCase("coupon-kitchen@fixtures.zaika.test").orElseThrow();
		mvc.perform(post("/cart/coupon").param("code", "ANYCODE").with(as(owner)).with(csrf()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/cart/coupon/remove").with(as(owner)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(get("/cart/sign-in").with(as(owner))).andExpect(status().isForbidden());

		mvc.perform(post("/cart/coupon").param("code", "ANYCODE").with(as(customerWith(thali, 1))))
			.andExpect(status().isForbidden());
	}

	@Test
	void myCouponNeverShowsOnAnotherCustomersCart() throws Exception {
		Coupon coupon = coupon(CouponType.FLAT, "45", c -> {
		});
		User riya = customerWith(thali, 1);
		User aarav = customerWith(thali, 1);
		mvc.perform(post("/cart/coupon").param("code", coupon.getCode()).with(as(riya)).with(csrf()));

		assertThat(cartPage(riya)).contains("<strong>" + coupon.getCode() + " applied</strong>");
		assertThat(cartPage(aarav)).doesNotContain(coupon.getCode() + " applied", "bill-discount");

		mvc.perform(post("/cart/coupon/remove").with(as(aarav)).with(csrf()));
		assertThat(cartPage(riya)).as("their remove is theirs").contains("<strong>" + coupon.getCode() + " applied</strong>");
	}

	// ---------------------------------------------------------------- restaurant page

	@Test
	void theRestaurantPageListsTheCouponsOpenThereNextToTheDeliveryOffer() throws Exception {
		Coupon welcome = coupon(CouponType.PERCENT, "50", c -> {
			c.setMaxDiscount(new BigDecimal("100"));
			c.setMinOrder(new BigDecimal("149"));
			c.setFirstOrderOnly(true);
		});

		String page = squash(mvc.perform(get("/r/other-kitchen")).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString());

		assertThat(page).contains("<strong>Free delivery above ₹499</strong>", "<strong>₹50 off</strong>",
				"<small>Use " + otherKitchenOnly.getCode() + " · Min. order ₹200</small>",
				"<strong>50% off up to ₹100</strong>",
				"<small>Use " + welcome.getCode() + " · Min. order ₹149 · First order only</small>", "#tag");
		assertThat(page.indexOf("Use " + otherKitchenOnly.getCode() + " ")).as("the kitchen's own coupon first")
			.isLessThan(page.indexOf("Use " + welcome.getCode() + " "));
		assertThat(squash(mvc.perform(get("/r/coupon-kitchen")).andReturn().getResponse().getContentAsString()))
			.doesNotContain("Use " + otherKitchenOnly.getCode() + " ");
	}

}
