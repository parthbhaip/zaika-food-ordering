package com.zaika.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.util.UUID;

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

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * The cart through the web: ADD from a restaurant page, steppers, the "Start a new cart?" page, the cart page's
 * re-check, bill, minimum order, note and checkout button, the phone cart bar, the guest cart moving into the account
 * on sign-in and sign-up, and who may use a cart. Every action as a plain form (no JavaScript) and as an htmx request
 * (fragments with the header count, cart bar and toasts out of band). Own database; the test clock says Sunday 19:30
 * IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cartpage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartPageTest {

	static final String PASSWORD = "Demo@1234";

	@Autowired
	MockMvc mvc;

	@Autowired
	CartService carts;

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

	Restaurant rassa;

	Restaurant sunrise;

	Restaurant morningOnly;

	Restaurant minimum;

	@BeforeAll
	void createRestaurants() {
		rassa = fixtures.restaurant("rassa-house", "Rassa House").save();
		sunrise = fixtures.restaurant("sunrise-misal", "Sunrise Misal").pureVeg().save();
		morningOnly = fixtures.restaurant("morning-only", "Morning Only").hours("06:00", "10:00").save();
		minimum = fixtures.restaurant("thali-company", "Thali Company").minimumOrder(300).deliveryFee(40).save();
	}

	MenuItem dish(Restaurant restaurant, String name, String price) {
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(restaurant.getId()).get(0);
		return items.save(new MenuItem(menu, name, new BigDecimal(price), restaurant.isPureVeg()));
	}

	User customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@cart.zaika.test", "9876543210",
				passwordEncoder.encode(PASSWORD), Role.CUSTOMER));
	}

	RequestPostProcessor as(User account) {
		return user(userDetails.loadUserByUsername(account.getEmail()));
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	String page(String url, MockHttpSession session) throws Exception {
		return squash(mvc.perform(get(url).session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	MvcResult add(MenuItem item, int quantity, MockHttpSession session) throws Exception {
		return mvc.perform(post("/cart/items/" + item.getId()).param("quantity", String.valueOf(quantity))
			.session(session).with(csrf())).andReturn();
	}

	/** An htmx stepper or ADD, as htmx sends it: the HX-Request header plus the form fields. */
	MvcResult htmx(MenuItem item, int quantity, String from, MockHttpSession session) throws Exception {
		var request = post("/cart/items/" + item.getId()).header("HX-Request", "true")
			.param("quantity", String.valueOf(quantity)).session(session).with(csrf());
		if (from != null) {
			request.param("from", from);
		}
		return mvc.perform(request).andExpect(status().isOk()).andReturn();
	}

	static String body(MvcResult result) throws Exception {
		return squash(result.getResponse().getContentAsString()).strip();
	}

	/** The header count as an htmx answer swaps it in out of band. */
	static String oobCount(int count) {
		return "<span id=\"cart-count\" hx-swap-oob=\"innerHTML\"><span class=\"cart-count" + (count == 0 ? " is-empty" : "")
				+ "\"><span>" + count + "</span><span class=\"visually-hidden\">" + (count == 1 ? " item" : " items")
				+ "</span></span></span>";
	}

	static String headerCount(String html) {
		int at = html.indexOf("id=\"cart-count\"");
		return html.substring(at, html.indexOf("</span>", at));
	}

	// ---------------------------------------------------------------- adding without JavaScript

	@Test
	void addFromTheRestaurantPageThenSeeItInTheCart() throws Exception {
		MenuItem thali = dish(rassa, "Mutton Thali", "420");
		MockHttpSession session = new MockHttpSession();
		String before = page("/r/rassa-house", session);
		assertThat(before).contains("action=\"/cart/items/" + thali.getId() + "\" method=\"post\"");

		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "1").session(session).with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/r/rassa-house#dish-" + thali.getId()))
			.andExpect(flash().attribute("flashSuccess", "Added Mutton Thali to your cart."));

		String after = page("/r/rassa-house", session);
		assertThat(after).contains("aria-label=\"Quantity of Mutton Thali\"", "<span class=\"qty-value\">1</span>");
		assertThat(headerCount(after)).contains("<span>1");
		String cart = page("/cart", session);
		assertThat(cart).contains("<title>Your cart | Zaika</title>", "Rassa House", "Mutton Thali",
				"Item total (1 item)", "₹420");
	}

	@Test
	void cartPageSteppersComeBackToTheCartAndZeroRemoves() throws Exception {
		MenuItem thali = dish(rassa, "Veg Thali", "260");
		MockHttpSession session = new MockHttpSession();
		add(thali, 2, session);
		String cart = page("/cart", session);
		assertThat(cart).contains("<input type=\"hidden\" name=\"from\" value=\"cart\">",
				"name=\"quantity\" value=\"1\" aria-label=\"Remove one Veg Thali\"",
				"name=\"quantity\" value=\"3\" aria-label=\"Add one more Veg Thali\"", "Item total (2 items)", "₹520");

		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "0").param("from", "cart").session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/cart"))
			.andExpect(flash().attribute("flashInfo", "Removed Veg Thali from your cart."));
		assertThat(page("/cart", session)).contains("Your cart is empty", "href=\"/restaurants\"");
	}

	@Test
	void moreThanTwentyIsCappedWithANote() throws Exception {
		MenuItem solkadhi = dish(rassa, "Solkadhi", "60");
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/cart/items/" + solkadhi.getId()).param("quantity", "50").session(session).with(csrf()))
			.andExpect(flash().attribute("flashInfo", "You can order up to 20 of a dish at a time."));
		String cart = page("/cart", session);
		assertThat(cart).contains("<span class=\"qty-value\">20</span>")
			.contains("value=\"21\" aria-label=\"Add one more Solkadhi\" id=\"line-" + solkadhi.getId() + "-more\" disabled=\"disabled\"");
	}

	@Test
	void soldOutDishesAndClosedKitchensSayWhy() throws Exception {
		MenuItem gone = dish(rassa, "Bombil Fry", "260");
		gone.setAvailable(false);
		items.save(gone);
		MockHttpSession session = new MockHttpSession();
		assertThat(add(gone, 1, session).getFlashMap().get("flashError")).isEqualTo("Bombil Fry just sold out.");
		assertThat(add(dish(morningOnly, "Poha", "50"), 1, session).getFlashMap().get("flashError"))
			.isEqualTo("Morning Only is not taking orders right now.");
		assertThat(page("/cart", session)).contains("Your cart is empty");
	}

	@Test
	void badQuantitiesAndUnknownDishesAreRejected() throws Exception {
		MenuItem thali = dish(rassa, "Fish Thali", "450");
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "-1").with(csrf()))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "two").with(csrf()))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/cart/items/" + Long.MAX_VALUE).param("quantity", "1").with(csrf()))
			.andExpect(status().isNotFound()).andExpect(view().name("error/404"));
	}

	@Test
	void addingNeedsTheCsrfToken() throws Exception {
		MenuItem thali = dish(rassa, "Egg Thali", "220");
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "1")).andExpect(status().isForbidden());
	}

	@Test
	void lookingAtAnEmptyCartStoresNoGuestCart() throws Exception {
		MockHttpSession session = new MockHttpSession();
		assertThat(page("/cart", session)).contains("Your cart is empty");
		assertThat(page("/r/rassa-house", session)).contains("class=\"btn btn-add\"");
		assertThat(session.getAttribute(GuestCart.SESSION_KEY)).isNull();
	}

	// ---------------------------------------------------------------- one restaurant per cart

	@Test
	void anotherKitchenAsksToStartANewCartWithoutJavaScript() throws Exception {
		MenuItem thali = dish(rassa, "Chicken Thali", "380");
		MenuItem misal = dish(sunrise, "Misal Pav", "90");
		MockHttpSession session = new MockHttpSession();
		add(thali, 2, session);

		mvc.perform(post("/cart/items/" + misal.getId()).param("quantity", "1").session(session).with(csrf()))
			.andExpect(redirectedUrl("/cart/replace/" + misal.getId() + "?quantity=1"));
		String prompt = page("/cart/replace/" + misal.getId() + "?quantity=1", session);
		assertThat(prompt).contains("<title>Start a new cart? | Zaika</title>",
				"Your cart has dishes from <strong>Rassa House</strong>",
				"Adding <strong>Misal Pav</strong> from <strong>Sunrise Misal</strong> will clear them",
				"<input type=\"hidden\" name=\"startNew\" value=\"true\">",
				"action=\"/cart/items/" + misal.getId() + "\" method=\"post\"",
				"href=\"/r/sunrise-misal\">Keep current cart</a>");
		assertThat(page("/cart", session)).contains("Chicken Thali").doesNotContain("Misal Pav");

		mvc.perform(post("/cart/items/" + misal.getId()).param("quantity", "1").param("startNew", "true")
			.session(session).with(csrf()))
			.andExpect(redirectedUrl("/r/sunrise-misal#dish-" + misal.getId()));
		assertThat(page("/cart", session)).contains("Sunrise Misal", "Misal Pav").doesNotContain("Chicken Thali");
		mvc.perform(get("/cart/replace/" + misal.getId()).session(session)).andExpect(redirectedUrl("/cart"));
	}

	// ---------------------------------------------------------------- re-checked on every view

	@Test
	void theCartPageShowsPriceChangesAndAClosedKitchen() throws Exception {
		User riya = customer();
		MenuItem upma = dish(morningOnly, "Upma", "70");
		MenuItem thali = dish(rassa, "Tambda Rassa Thali", "420");
		CartOwner owner = new CartOwner.Customer(riya.getId());
		carts.setQuantity(owner, thali.getId(), 1, false);
		thali.setPrice(new BigDecimal("440"));
		items.save(thali);

		String cart = squash(mvc.perform(get("/cart").with(as(riya))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(cart).contains("Tambda Rassa Thali now costs ₹440 (was ₹420).", "₹440");

		carts.clear(owner);
		var guest = new GuestCart();
		guest.put(items.findWithRestaurantById(upma.getId()).orElseThrow(), 1); // added while it was open
		assertThat(carts.merge(guest, riya.getId())).isNull();
		cart = squash(mvc.perform(get("/cart").with(as(riya))).andReturn().getResponse().getContentAsString());
		assertThat(cart).contains("Morning Only is closed now and opens at 6:00 AM. Your cart will be here.")
			.contains("value=\"2\" aria-label=\"Add one more Upma\" id=\"line-" + upma.getId() + "-more\" disabled=\"disabled\"")
			.contains("<button type=\"button\" class=\"btn btn-primary btn-lg btn-block\" disabled>Opens at 6:00 AM</button>")
			.doesNotContain("href=\"/checkout\"");
	}

	// ---------------------------------------------------------------- guest cart on sign-in and sign-up

	@Test
	void signingInMovesTheGuestCartIntoTheAccount() throws Exception {
		User riya = customer();
		MenuItem kept = dish(rassa, "Kolhapuri Misal", "120");
		MenuItem misal = dish(sunrise, "Puneri Misal", "95");
		carts.setQuantity(new CartOwner.Customer(riya.getId()), kept.getId(), 1, false);
		MockHttpSession session = new MockHttpSession();
		add(misal, 2, session);

		mvc.perform(post("/login").param("email", riya.getEmail()).param("password", PASSWORD).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/"));

		assertThat(carts.quantities(new CartOwner.Customer(riya.getId()))).containsExactly(entry(misal.getId(), 2));
		assertThat(session.getAttribute(GuestCart.SESSION_KEY)).isNull();
		String home = page("/", session);
		assertThat(home).contains(
				"Your cart now has the dishes you just picked. We cleared your earlier cart from Rassa House.");
		assertThat(headerCount(home)).contains("<span>2");
	}

	@Test
	void signingUpMovesTheGuestCartIntoTheNewAccount() throws Exception {
		MenuItem misal = dish(sunrise, "Tarri Misal", "110");
		MockHttpSession session = new MockHttpSession();
		add(misal, 3, session);
		String email = "new-" + UUID.randomUUID() + "@cart.zaika.test";

		mvc.perform(post("/register").param("name", "Asha Patil").param("email", email).param("phone", "9876543210")
			.param("password", "Masala2026").param("confirmPassword", "Masala2026").param("acceptTerms", "true")
			.session(session).with(csrf()))
			.andExpect(redirectedUrl("/"));

		User asha = users.findByEmailIgnoreCase(email).orElseThrow();
		assertThat(carts.quantities(new CartOwner.Customer(asha.getId()))).containsExactly(entry(misal.getId(), 3));
		assertThat(headerCount(page("/", session))).contains("<span>3");
	}

	// ---------------------------------------------------------------- whose cart

	@Test
	void anotherCustomerNeverSeesMyCart() throws Exception {
		User riya = customer();
		User aarav = customer();
		MenuItem thali = dish(rassa, "Sukka Mutton Thali", "460");
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "2").with(as(riya)).with(csrf()))
			.andExpect(status().isFound());

		String mine = squash(mvc.perform(get("/cart").with(as(riya))).andReturn().getResponse().getContentAsString());
		String theirs = squash(mvc.perform(get("/cart").with(as(aarav))).andReturn().getResponse().getContentAsString());
		assertThat(mine).contains("Sukka Mutton Thali");
		assertThat(headerCount(mine)).contains("<span>2");
		assertThat(theirs).contains("Your cart is empty").doesNotContain("Sukka Mutton Thali");
		assertThat(headerCount(theirs)).contains("<span>0");
	}

	@Test
	void staffAccountsHaveNoCart() throws Exception {
		MenuItem thali = dish(rassa, "Prawn Thali", "520");
		User owner = users.findByEmailIgnoreCase("rassa-house@fixtures.zaika.test").orElseThrow();
		mvc.perform(get("/cart").with(as(owner))).andExpect(status().isForbidden());
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "1").with(as(owner)).with(csrf()))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- htmx: fragments, count, cart bar and toasts out of band

	@Test
	void htmxAddOnTheRestaurantPageSwapsJustTheDishAndUpdatesTheHeaderBarAndToast() throws Exception {
		MenuItem thali = dish(rassa, "Kolhapuri Thali", "420");
		MockHttpSession session = new MockHttpSession();

		String added = body(htmx(thali, 1, null, session));
		assertThat(added).startsWith("<article class=\"menu-item\" id=\"dish-" + thali.getId() + "\">")
			.contains("aria-label=\"Quantity of Kolhapuri Thali\"", "<span class=\"qty-value\">1</span>",
					"hx-target=\"#dish-" + thali.getId() + "\"")
			.contains(oobCount(1))
			.contains("<div id=\"cart-bar-slot\" hx-swap-oob=\"true\"> <a class=\"cart-bar\" href=\"/cart\">",
					"1 item · ₹420", "From Rassa House")
			.contains("<div hx-swap-oob=\"beforeend:#toasts\">", "Added Kolhapuri Thali to your cart.")
			.doesNotContain("<html", "<header", "<footer", "autofocus");

		String more = body(htmx(thali, 2, null, session));
		assertThat(more).contains("<span class=\"qty-value\">2</span>", oobCount(2), "2 items · ₹840")
			.doesNotContain("hx-swap-oob=\"beforeend:#toasts\"");

		String removed = body(htmx(thali, 0, null, session));
		assertThat(removed).contains("class=\"btn btn-add\" id=\"dish-" + thali.getId() + "-more\" autofocus=\"autofocus\"")
			.contains(oobCount(0), "<div id=\"cart-bar-slot\" hx-swap-oob=\"true\"> </div>",
					"Removed Kolhapuri Thali from your cart.")
			.doesNotContain("class=\"cart-bar\"");
		assertThat(page("/cart", session)).contains("Your cart is empty");
	}

	@Test
	void htmxSaysWhyNothingWasAdded() throws Exception {
		MenuItem gone = dish(rassa, "Surmai Fry", "380");
		gone.setAvailable(false);
		items.save(gone);
		MenuItem solkadhi = dish(rassa, "Kokum Solkadhi", "60");
		MockHttpSession session = new MockHttpSession();

		assertThat(body(htmx(gone, 1, null, session))).contains("role=\"alert\"", "Surmai Fry just sold out.", oobCount(0));
		assertThat(body(htmx(solkadhi, 25, null, session))).contains("You can order up to 20 of a dish at a time.",
				oobCount(20), "<span class=\"qty-value\">20</span>",
				"id=\"dish-" + solkadhi.getId() + "-more\" aria-label=\"Add one more Kokum Solkadhi\" disabled=\"disabled\"");
	}

	@Test
	void htmxAddFromAnotherKitchenGoesToTheStartANewCartPage() throws Exception {
		MenuItem thali = dish(rassa, "Pandhra Rassa Thali", "400");
		MenuItem misal = dish(sunrise, "Upvas Misal", "100");
		MockHttpSession session = new MockHttpSession();
		add(thali, 1, session);

		MvcResult result = htmx(misal, 1, null, session);
		assertThat(result.getResponse().getHeader("HX-Redirect")).isEqualTo("/cart/replace/" + misal.getId() + "?quantity=1");
		assertThat(result.getResponse().getContentAsString()).isBlank();
		assertThat(page("/cart", session)).contains("Pandhra Rassa Thali").doesNotContain("Upvas Misal");
	}

	@Test
	void htmxSteppersOnTheCartPageSwapTheDishesAndTheBill() throws Exception {
		MenuItem thali = dish(rassa, "Sukka Chicken Thali", "200");
		MenuItem bhakri = dish(rassa, "Jowar Bhakri", "30");
		MockHttpSession session = new MockHttpSession();
		add(thali, 1, session);
		add(bhakri, 1, session);

		String more = body(htmx(thali, 2, "cart", session));
		assertThat(more).startsWith("<div id=\"cart-items\" class=\"cart-items\">")
			.contains("Item total (3 items)", "₹430.00")
			.contains("<div id=\"cart-side\" class=\"cart-side\" hx-swap-oob=\"true\">")
			// 430 + 30 delivery + 5 platform + 21.50 GST
			.contains("<dt>Item total</dt><dd>₹430.00</dd>", "<dt>Delivery fee</dt> <dd>₹30.00</dd>",
					"<dt>GST (5% on food)</dt><dd>₹21.50</dd>", "<dt>To pay</dt><dd>₹486.50</dd>")
			.contains(oobCount(3))
			.doesNotContain("<html", "cart-bar-slot", "autofocus");

		String less = body(htmx(bhakri, 0, "cart", session));
		assertThat(less).contains("Removed Jowar Bhakri from your cart.", oobCount(2),
				"id=\"cart-restaurant\" tabindex=\"-1\" autofocus=\"autofocus\"")
			.doesNotContain("Jowar Bhakri</p>");

		MvcResult last = htmx(thali, 0, "cart", session);
		assertThat(last.getResponse().getHeader("HX-Retarget")).isEqualTo("#cart-body");
		assertThat(last.getResponse().getHeader("HX-Reswap")).isEqualTo("outerHTML");
		assertThat(body(last)).startsWith("<div id=\"cart-body\" class=\"cart-body is-empty\">")
			.contains("id=\"cart-title\" tabindex=\"-1\" autofocus=\"autofocus\"", "Your cart is empty", oobCount(0))
			.doesNotContain("cart-side");
	}

	@Test
	void cartPageFormsPostWithoutJavaScriptAndUseHtmxWhenItIsThere() throws Exception {
		MenuItem thali = dish(rassa, "Mutton Kheema Thali", "350");
		MockHttpSession session = new MockHttpSession();
		add(thali, 1, session);
		String cart = page("/cart", session);
		assertThat(cart).contains("action=\"/cart/items/" + thali.getId() + "\" method=\"post\" hx-post=\"/cart/items/"
				+ thali.getId() + "\" hx-target=\"#cart-items\" hx-swap=\"outerHTML\"")
			.contains("action=\"/cart/instructions\" method=\"post\" hx-post=\"/cart/instructions\" hx-target=\"this\"");
	}

	// ---------------------------------------------------------------- bill, minimum order and checkout

	@Test
	void theCartPageShowsTheBillAndTheFreeDeliveryNudge() throws Exception {
		MenuItem thali = dish(rassa, "Tambda Thali", "420");
		MockHttpSession session = new MockHttpSession();
		add(thali, 1, session);

		String cart = page("/cart", session);
		// 420 + 30 delivery + 5 platform + 21 GST
		assertThat(cart).contains("<h2 class=\"cart-bill-title\" id=\"cart-bill-title\">Bill details</h2>",
				"<dt>Item total</dt><dd>₹420.00</dd>", "<dt>Delivery fee</dt> <dd>₹30.00</dd>",
				"<dt>Platform fee</dt><dd>₹5.00</dd>", "<dt>GST (5% on food)</dt><dd>₹21.00</dd>",
				"<dt>To pay</dt><dd>₹476.00</dd>", "Add ₹80 more for free delivery")
			.contains("<span>Sign in to check out</span>", "href=\"/checkout\"")
			.doesNotContain("Coupon discount", "cart-minimum", "class=\"cart-bar\"");

		add(thali, 2, session); // ₹840, above ₹499
		assertThat(page("/cart", session))
			.contains("<dt>Delivery fee</dt> <dd><s class=\"muted\">₹30.00</s> <span class=\"bill-free\">FREE</span></dd>",
					"<dt>To pay</dt><dd>₹887.00</dd>")
			.doesNotContain("for free delivery");
	}

	@Test
	void belowTheMinimumOrderTheCartSaysHowMuchMoreAndCheckoutWaits() throws Exception {
		User riya = customer();
		MenuItem thali = dish(minimum, "Veg Kolhapuri Thali", "120");
		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "1").with(as(riya)).with(csrf()));

		String cart = squash(mvc.perform(get("/cart").with(as(riya))).andReturn().getResponse().getContentAsString());
		assertThat(cart).contains(
				"<strong>Add ₹180 more</strong> <span>to place this order. Thali Company takes orders from ₹300.</span>",
				"<progress class=\"progress\" max=\"300.00\" value=\"120.00\" aria-label=\"Progress to the minimum order\">₹120 of ₹300</progress>",
				"<button type=\"button\" class=\"btn btn-primary btn-lg btn-block\" disabled>Add ₹180 more to check out</button>")
			.doesNotContain("href=\"/checkout\"");

		mvc.perform(post("/cart/items/" + thali.getId()).param("quantity", "3").with(as(riya)).with(csrf()));
		cart = squash(mvc.perform(get("/cart").with(as(riya))).andReturn().getResponse().getContentAsString());
		// 360 + 40 delivery + 5 platform + 18 GST
		assertThat(cart).contains("href=\"/checkout\"", "<span>Proceed to checkout</span>",
				"<span class=\"cart-checkout-total\">₹423.00</span>")
			.doesNotContain("cart-minimum", "Sign in to check out", "disabled>Add");
	}

	// ---------------------------------------------------------------- note for the restaurant

	@Test
	void theNoteIsSavedShownAgainAndValidated() throws Exception {
		MenuItem thali = dish(rassa, "Chicken Sukka Thali", "390");
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/cart/instructions").param("instructions", "No onion").session(session).with(csrf()))
			.andExpect(redirectedUrl("/cart")).andExpect(flash().attributeCount(0));
		add(thali, 1, session);

		mvc.perform(post("/cart/instructions").param("instructions", "  Less spicy, <b>no</b> onion ")
			.session(session).with(csrf()))
			.andExpect(redirectedUrl("/cart"))
			.andExpect(flash().attribute("flashSuccess", "Note saved. The kitchen will see it with your order."));
		assertThat(page("/cart", session)).contains(
				"<label class=\"field-label\" for=\"instructions\">Note for the restaurant</label>", "maxlength=\"250\"",
				">Less spicy, &lt;b&gt;no&lt;/b&gt; onion</textarea>");

		String tooLong = squash(mvc.perform(post("/cart/instructions").param("instructions", "a".repeat(251))
			.session(session).with(csrf()))
			.andExpect(status().isOk()).andExpect(view().name("cart/cart"))
			.andReturn().getResponse().getContentAsString());
		assertThat(tooLong).contains("Keep the note to 250 characters or fewer.", "aria-invalid=\"true\"",
				">" + "a".repeat(251) + "</textarea>", "Bill details");
		assertThat(page("/cart", session)).contains(">Less spicy, &lt;b&gt;no&lt;/b&gt; onion</textarea>");

		mvc.perform(post("/cart/instructions").param("instructions", "").session(session).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Note removed."));
		assertThat(page("/cart", session)).contains("aria-describedby=\"instructions-help\"></textarea>");
	}

	@Test
	void htmxSavesTheNoteInPlace() throws Exception {
		User riya = customer();
		MenuItem thali = dish(rassa, "Egg Curry Thali", "240");
		CartOwner owner = new CartOwner.Customer(riya.getId());
		carts.setQuantity(owner, thali.getId(), 1, false);

		String saved = squash(mvc.perform(post("/cart/instructions").header("HX-Request", "true")
			.param("instructions", "Please pack the rassa separately").with(as(riya)).with(csrf()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).strip();
		assertThat(saved).startsWith("<form id=\"cart-note\"")
			.contains(">Please pack the rassa separately</textarea>", "Note saved. The kitchen will see it with your order.")
			.doesNotContain("<html");
		assertThat(carts.view(owner).instructions()).isEqualTo("Please pack the rassa separately");

		String invalid = squash(mvc.perform(post("/cart/instructions").header("HX-Request", "true")
			.param("instructions", "b".repeat(300)).with(as(riya)).with(csrf()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(invalid).contains("Keep the note to 250 characters or fewer.").doesNotContain("hx-swap-oob");
		assertThat(carts.view(owner).instructions()).isEqualTo("Please pack the rassa separately");
	}

	// ---------------------------------------------------------------- phone "View cart" bar

	@Test
	void thePhoneCartBarShowsOnceTheCartHasDishesButNotOnTheCartPage() throws Exception {
		MenuItem misal = dish(sunrise, "Misal Pav Combo", "150");
		MockHttpSession session = new MockHttpSession();
		assertThat(page("/r/sunrise-misal", session)).contains("<div id=\"cart-bar-slot\"> </div>")
			.doesNotContain("class=\"cart-bar\"");

		add(misal, 2, session);
		assertThat(page("/r/sunrise-misal", session)).contains("<a class=\"cart-bar\" href=\"/cart\">",
				"<span>2 items · ₹300</span>", "<small>From Sunrise Misal</small>", "View cart");
		String about = page("/about", session); // a view-controller page: no controller, still the cart in the layout
		assertThat(about).contains("<a class=\"cart-bar\" href=\"/cart\">");
		assertThat(headerCount(about)).contains("<span>2");
		assertThat(page("/cart", session)).doesNotContain("class=\"cart-bar\"");

		User owner = users.findByEmailIgnoreCase("sunrise-misal@fixtures.zaika.test").orElseThrow();
		assertThat(squash(mvc.perform(get("/r/sunrise-misal").with(as(owner))).andReturn().getResponse()
			.getContentAsString())).doesNotContain("class=\"cart-bar\"");
	}

	@Test
	void staffCannotUseTheCartThroughHtmxOrTheNoteForm() throws Exception {
		MenuItem thali = dish(rassa, "Crab Thali", "560");
		User owner = users.findByEmailIgnoreCase("rassa-house@fixtures.zaika.test").orElseThrow();
		mvc.perform(post("/cart/items/" + thali.getId()).header("HX-Request", "true").param("quantity", "1")
			.with(as(owner)).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/cart/instructions").param("instructions", "x").with(as(owner)).with(csrf()))
			.andExpect(status().isForbidden());
		mvc.perform(post("/cart/instructions").param("instructions", "x")).andExpect(status().isForbidden());
	}

}
