package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.OrderFixtures;
import com.zaika.user.User;

/**
 * "Reorder" from {@code /orders} and {@code /orders/{code}}: available dishes go into the cart in the old quantities,
 * the cart page names left-out dishes and changed prices, refusals go back to the order with the reason, and only the
 * order's customer can do it. Own database; the test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" }) // same context as OrderPagesTest (cached, not a new one)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReorderPagesTest {

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
	CartService carts;

	@Autowired
	ZaikaUserDetailsService details;

	User customer;

	Restaurant restaurant;

	MenuItem thali; // ₹199 like every fixture dish

	MenuItem bhakri;

	MenuItem solkadhi;

	@BeforeEach
	void setUp() {
		customer = orders.customer();
		restaurant = fixtures.restaurant("reorder-" + UUID.randomUUID().toString().substring(0, 8), "Reorder Kitchen")
			.dishes("Mutton Thali", "Jowar Bhakri", "Solkadhi").save();
		var menu = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId());
		thali = dish(menu, "Mutton Thali");
		bhakri = dish(menu, "Jowar Bhakri");
		solkadhi = dish(menu, "Solkadhi");
	}

	static MenuItem dish(java.util.List<MenuItem> menu, String name) {
		return menu.stream().filter(i -> i.getName().equals(name)).findFirst().orElseThrow();
	}

	UserDetails as(User u) {
		return details.loadUserByUsername(u.getEmail());
	}

	CartOwner cartOf(User u) {
		return new CartOwner.Customer(u.getId());
	}

	Order delivered(MenuItem dish, int quantity) {
		return orders.place(customer.getId(), dish.getId(), quantity, PaymentMethod.COD, OrderStatus.DELIVERED);
	}

	void change(MenuItem dish, java.util.function.Consumer<MenuItem> edit) {
		MenuItem stored = items.findById(dish.getId()).orElseThrow();
		edit.accept(stored);
		items.save(stored);
	}

	void changeRestaurant(java.util.function.Consumer<Restaurant> edit) {
		Restaurant stored = restaurants.findById(restaurant.getId()).orElseThrow();
		edit.accept(stored);
		restaurants.save(stored);
	}

	MvcResult reorder(Order order) throws Exception {
		return mvc.perform(post("/orders/" + order.getCode() + "/reorder").with(csrf()).with(user(as(customer))))
			.andReturn();
	}

	String cartPage(MvcResult afterReorder) throws Exception {
		return mvc.perform(get("/cart").with(user(as(customer))).flashAttrs(afterReorder.getFlashMap()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	// ---------------------------------------------------------------- what goes into the cart

	@Test
	void availableDishesGoIntoTheCartAndTheCartNamesLeftOutDishesAndNewPrices() throws Exception {
		Order order = delivered(thali, 2);
		orders.addLine(order, bhakri, 3);
		orders.addLine(order, solkadhi, 1);
		change(thali, d -> d.setPrice(new BigDecimal("229")));
		change(bhakri, d -> d.setAvailable(false));
		change(solkadhi, d -> d.setArchived(true));

		MvcResult result = reorder(order);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/cart");
		assertThat(result.getFlashMap().get("flashSuccess")).isEqualTo("Added 1 dish from " + order.getCode() + " to your cart.");
		assertThat(result.getFlashMap().get("reorderNotice"))
			.isEqualTo("Jowar Bhakri and Solkadhi are not available right now, so we left them out.");

		String cart = cartPage(result);
		assertThat(cart).contains("Jowar Bhakri and Solkadhi are not available right now, so we left them out.",
				"Mutton Thali now costs ₹229 (was ₹199).", "Added 1 dish from " + order.getCode() + " to your cart.");
		var view = carts.view(cartOf(customer));
		assertThat(view.lines()).singleElement().satisfies(line -> {
			assertThat(line.item().getId()).isEqualTo(thali.getId());
			assertThat(line.quantity()).isEqualTo(2);
		});
		assertThat(view.notices()).as("the price change shows once").isEmpty();
	}

	@Test
	void reorderReplacesAnotherRestaurantsCartWithTheOrdersQuantities() throws Exception {
		Restaurant other = fixtures.restaurant("reorder-other-" + UUID.randomUUID().toString().substring(0, 6),
				"Other Kitchen").dishes("Misal Pav").save();
		MenuItem misal = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(other.getId()).getFirst();
		carts.setQuantity(cartOf(customer), misal.getId(), 2, false);
		Order order = delivered(thali, CartService.MAX_QUANTITY);

		MvcResult result = reorder(order);
		assertThat(result.getFlashMap().get("flashSuccess")).isEqualTo(
				"Your cart now has the 1 dish from " + order.getCode() + ". We took out what was in it before.");
		assertThat(result.getFlashMap().get("reorderNotice")).isNull();
		var view = carts.view(cartOf(customer));
		assertThat(view.restaurant().getId()).isEqualTo(restaurant.getId());
		assertThat(view.lines()).singleElement()
			.satisfies(line -> assertThat(line.quantity()).isEqualTo(CartService.MAX_QUANTITY));
		assertThat(view.notices()).isEmpty();
	}

	@Test
	void sameRestaurantCartKeepsItsNoteAndGetsTheOrdersQuantities() throws Exception {
		Order order = delivered(thali, 1);
		carts.setQuantity(cartOf(customer), thali.getId(), 4, false);
		carts.saveInstructions(cartOf(customer), "Less spicy please");

		MvcResult result = reorder(order);
		assertThat(result.getFlashMap().get("flashSuccess")).isEqualTo("Added 1 dish from " + order.getCode() + " to your cart.");
		var view = carts.view(cartOf(customer));
		assertThat(view.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(1));
		assertThat(view.instructions()).isEqualTo("Less spicy please");
	}

	// ---------------------------------------------------------------- refusals

	@Test
	void openOrdersAndClosedOrPausedKitchensAreRefusedWithTheReason() throws Exception {
		Order open = orders.place(customer.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.PREPARING);
		mvc.perform(post("/orders/" + open.getCode() + "/reorder").with(csrf()).with(user(as(customer))))
			.andExpect(redirectedUrl("/orders/" + open.getCode()))
			.andExpect(flash().attribute("flashError", open.getCode() + " is still open. You can order it again once it's done."));

		Order done = delivered(thali, 1);
		changeRestaurant(r -> r.setHours(LocalTime.of(6, 0), LocalTime.of(10, 0)));
		mvc.perform(post("/orders/" + done.getCode() + "/reorder").with(csrf()).with(user(as(customer))))
			.andExpect(redirectedUrl("/orders/" + done.getCode()))
			.andExpect(flash().attribute("flashError", "Reorder Kitchen is closed now. It opens at 6:00 AM, so order again then."));

		changeRestaurant(r -> {
			r.setHours(LocalTime.of(11, 0), LocalTime.of(23, 0));
			r.setAcceptingOrders(false);
		});
		mvc.perform(post("/orders/" + done.getCode() + "/reorder").with(csrf()).with(user(as(customer))))
			.andExpect(flash().attribute("flashError", "Reorder Kitchen has paused new orders for a little while. Try again soon."));

		changeRestaurant(r -> {
			r.setAcceptingOrders(true);
			r.changeStatus(RestaurantStatus.SUSPENDED, "Hygiene check");
		});
		mvc.perform(post("/orders/" + done.getCode() + "/reorder").with(csrf()).with(user(as(customer))))
			.andExpect(flash().attribute("flashError", "Reorder Kitchen is not taking orders on Zaika right now."));
		assertThat(carts.view(cartOf(customer)).lines()).isEmpty();
	}

	@Test
	void nothingAvailableLeavesTheCartAlone() throws Exception {
		Order order = delivered(bhakri, 1);
		carts.setQuantity(cartOf(customer), thali.getId(), 3, false);
		change(bhakri, d -> d.setAvailable(false));

		String page = mvc.perform(post("/orders/" + order.getCode() + "/reorder").with(csrf()).with(user(as(customer))))
			.andExpect(redirectedUrl("/orders/" + order.getCode())).andReturn().getFlashMap().get("flashError").toString();
		assertThat(page).isEqualTo("None of the dishes from " + order.getCode()
				+ " are available at Reorder Kitchen right now. Have a look at today's menu.");
		assertThat(carts.view(cartOf(customer)).lines()).singleElement()
			.satisfies(line -> assertThat(line.item().getId()).isEqualTo(thali.getId()));
	}

	// ---------------------------------------------------------------- where the button shows

	@Test
	void reorderButtonsShowOnFinishedOrdersOfLiveRestaurantsOnly() throws Exception {
		Order done = delivered(thali, 1);
		Order open = orders.place(customer.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.ACCEPTED);
		String list = mvc.perform(get("/orders").with(user(as(customer)))).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString();
		assertThat(list).contains("action=\"/orders/" + done.getCode() + "/reorder\"")
			.doesNotContain("action=\"/orders/" + open.getCode() + "/reorder\"");
		assertThat(mvc.perform(get("/orders/" + done.getCode()).with(user(as(customer)))).andReturn().getResponse()
			.getContentAsString()).contains("action=\"/orders/" + done.getCode() + "/reorder\"", "Reorder these dishes");

		changeRestaurant(r -> r.changeStatus(RestaurantStatus.SUSPENDED, "Hygiene check"));
		assertThat(mvc.perform(get("/orders").with(user(as(customer)))).andReturn().getResponse().getContentAsString())
			.doesNotContain("/reorder\"");
	}

	// ---------------------------------------------------------------- security

	@Test
	void onlyTheOrdersCustomerCanReorderIt() throws Exception {
		Order order = delivered(thali, 1);
		String url = "/orders/" + order.getCode() + "/reorder";
		User stranger = orders.customer();
		mvc.perform(post(url).with(csrf()).with(user(as(stranger)))).andExpect(status().isNotFound());
		assertThat(carts.view(cartOf(stranger)).lines()).isEmpty();
		mvc.perform(post(url).with(csrf()).with(anonymous())).andExpect(redirectedUrl("/login"));
		mvc.perform(post(url).with(csrf())
			.with(user(details.loadUserByUsername(restaurant.getSlug() + "@fixtures.zaika.test"))))
			.andExpect(status().isForbidden());
		mvc.perform(post(url).with(csrf()).with(user(as(orders.rider())))).andExpect(status().isForbidden());
		mvc.perform(post(url).with(user(as(customer)))).andExpect(status().isForbidden()); // no CSRF token
		mvc.perform(post("/orders/ZK-26-999999/reorder").with(csrf()).with(user(as(customer))))
			.andExpect(status().isNotFound());
		assertThat(carts.view(cartOf(customer)).lines()).isEmpty();
	}

}
