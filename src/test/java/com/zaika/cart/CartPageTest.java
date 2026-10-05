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
 * The cart through the web, all as plain forms (no JavaScript): ADD from a restaurant page, steppers, the
 * "Start a new cart?" page, the cart page's re-check, the guest cart moving into the account on sign-in and sign-up,
 * and who may use a cart. Own database; the test clock says Sunday 19:30 IST.
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

	@BeforeAll
	void createRestaurants() {
		rassa = fixtures.restaurant("rassa-house", "Rassa House").save();
		sunrise = fixtures.restaurant("sunrise-misal", "Sunrise Misal").pureVeg().save();
		morningOnly = fixtures.restaurant("morning-only", "Morning Only").hours("06:00", "10:00").save();
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
			.contains("value=\"21\" aria-label=\"Add one more Solkadhi\" disabled=\"disabled\"");
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
			.contains("value=\"2\" aria-label=\"Add one more Upma\" disabled=\"disabled\"");
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

}
