package com.zaika.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;

/**
 * {@code /r/{slug}} on its own database. "Rassa House" has two filled sections (plus two empty ones that must not
 * show), a sold-out dish and an archived one; other fixtures cover closed, paused, overnight, 24-hour, free-delivery,
 * empty and non-approved restaurants. The test clock says Sunday 19:30 IST unless a test moves it.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:restaurantpage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(RestaurantPageTest.CartProbe.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestaurantPageTest {

	static final Pattern DISH_NAME = Pattern.compile("<h4 class=\"menu-item-name\">([^<]+)</h4>");

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	MutableClock clock;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	MenuCategory thalis;

	MenuCategory sides;

	MenuItem muttonThali;

	MenuItem kheema;

	@BeforeAll
	void createRestaurants() {
		Restaurant rassa = fixtures.restaurant("rassa-house", "Rassa House").cuisines("Kolhapuri", "Maharashtrian")
			.rated("4.5", 2140).save(); // no dishes: its default "Menu" section stays empty
		sides = categories.save(new MenuCategory(rassa, "Sides", 3));
		thalis = categories.save(new MenuCategory(rassa, "Thalis", 2));
		categories.save(new MenuCategory(rassa, "Desserts", 4)); // empty
		muttonThali = dish(thalis, "Mutton Thali", "420", false, 1, "Sukka mutton, both rassas and two bhakris.");
		muttonThali.setBestseller(true);
		muttonThali.setSpiceLevel(3);
		muttonThali = items.save(muttonThali);
		MenuItem vegThali = dish(thalis, "Veg Kolhapuri Thali", "260", true, 2, "Pithla, bhakri and green thecha.");
		vegThali.setSpiceLevel(2);
		items.save(vegThali);
		kheema = dish(thalis, "Kheema Masala", "300", false, 3, "Minced mutton with green peas.");
		kheema.setAvailable(false);
		kheema = items.save(kheema);
		MenuItem archived = dish(thalis, "Old Paneer Pasta", "250", true, 4, "Off the menu.");
		archived.setArchived(true);
		items.save(archived);
		MenuItem rassaBowl = dish(sides, "Pandhra Rassa", "90.50", false, 1, null);
		rassaBowl.setSpiceLevel(1);
		items.save(rassaBowl);
		items.save(dish(sides, "Solkadhi", "60", true, 2, "Cooling kokum and coconut milk drink."));

		fixtures.restaurant("sunrise-misal", "Sunrise Misal").cuisines("Street Food").pureVeg().hours("06:00", "10:00")
			.dishes("Misal Pav").save();
		fixtures.restaurant("paused-pizza", "Paused Pizza Co").cuisines("Pizza").paused().dishes("Margherita").save();
		fixtures.restaurant("night-owl", "Night Owl Biryani").cuisines("Biryani").hours("18:00", "03:00")
			.dishes("Chicken Dum Biryani").save();
		fixtures.restaurant("round-clock", "Round The Clock Cafe").cuisines("Street Food").hours("00:00", "00:00")
			.dishes("Cutting Chai").save();
		fixtures.restaurant("free-ride", "Free Ride Pizza").cuisines("Pizza").deliveryFee(0).dishes("Farmhouse").save();
		fixtures.restaurant("empty-kitchen", "Empty Kitchen").cuisines("Pizza").save();
		fixtures.restaurant("pending-place", "Pending Place").cuisines("Pizza").status(RestaurantStatus.PENDING)
			.dishes("Margherita").save();
		fixtures.restaurant("suspended-place", "Suspended Place").cuisines("Pizza")
			.status(RestaurantStatus.SUSPENDED).dishes("Margherita").save();
		fixtures.restaurant("rejected-place", "Rejected Place").cuisines("Pizza").status(RestaurantStatus.REJECTED)
			.dishes("Margherita").save();
	}

	static MenuItem dish(MenuCategory category, String name, String price, boolean veg, int sortOrder,
			String description) {
		MenuItem item = new MenuItem(category, name, new BigDecimal(price), veg);
		item.setSortOrder(sortOrder);
		item.setDescription(description);
		return item;
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static MockHttpServletRequestBuilder at(String url) {
		return get(URI.create(url));
	}

	String html(String url) throws Exception {
		return mvc.perform(at(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	static List<String> dishes(String html) {
		List<String> names = new ArrayList<>();
		Matcher m = DISH_NAME.matcher(html);
		while (m.find()) {
			names.add(m.group(1));
		}
		return names;
	}

	/** The markup of one dish row. */
	static String row(String html, String dishName) {
		int name = html.indexOf(">" + dishName + "</h4>");
		assertThat(name).as("row for %s", dishName).isPositive();
		return html.substring(html.lastIndexOf("<article", name), html.indexOf("</article>", name));
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	// ---------------------------------------------------------------- header

	@Test
	void anonymousVisitorsSeeTheRestaurantHeader() throws Exception {
		String html = mvc.perform(at("/r/rassa-house"))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/restaurant"))
			.andExpect(header().string("Vary", "HX-Request"))
			.andReturn().getResponse().getContentAsString();
		assertThat(squash(html))
			.contains("<title>Rassa House | Zaika</title>")
			.contains("src=\"/placeholder/restaurant/rassa-house.svg\"") // cover
			.contains("src=\"/placeholder/logo/rassa-house.svg\" alt=\"Rassa House logo\"")
			.contains("<h1 id=\"rp-name\">Rassa House</h1>")
			.contains("<p class=\"rp-cuisines\">Kolhapuri, Maharashtrian</p>")
			.contains("12, Main Road, Rajarampuri")
			.contains("4.5<span class=\"visually-hidden\"> out of 5, 2,140 ratings</span>", ">2,140 ratings</span>")
			.contains("<strong>30-35 min</strong><small>Delivery time</small>")
			.contains("<strong>₹400</strong><small>Cost for two</small>")
			.contains("<strong>11:00 AM – 11:00 PM</strong><small>Today's timings</small>")
			.contains(">Open now</span>", "Closes at 11:00 PM")
			.contains("<ul class=\"rp-offers\" aria-label=\"Offers\">")
			.contains("<strong>Free delivery above ₹499</strong><small>₹30 delivery fee below that</small>")
			.doesNotContain("Closed now", "Not taking orders", "alert-warning");
	}

	@Test
	void aRestaurantWithoutADeliveryFeeOffersFreeDelivery() throws Exception {
		assertThat(squash(html("/r/free-ride")))
			.contains("<strong>Free delivery</strong><small>On every order from this kitchen</small>")
			.doesNotContain("delivery fee below that");
	}

	@Test
	void aRestaurantClosedByItsHoursSaysWhenItOpensAndCannotTakeOrders() throws Exception {
		String html = squash(html("/r/sunrise-misal"));
		assertThat(html)
			.contains(">Closed now</span>", "Opens at 6:00 AM", "<strong>6:00 AM – 10:00 AM</strong>")
			.contains("class=\"alert alert-warning rp-notice\"", "<strong>Closed now.</strong> Opens at 6:00 AM.")
			.contains("class=\"rp-cover is-closed\"")
			.doesNotContain(">Open now</span>", "action=\"/cart/items/");
		assertThat(row(html, "Misal Pav")).contains("disabled>ADD", "Misal Pav, ordering is closed");
	}

	@Test
	void aPausedKitchenIsNotTakingOrders() throws Exception {
		String html = squash(html("/r/paused-pizza"));
		assertThat(html)
			.contains(">Not taking orders</span>", "<strong>Not taking orders right now.</strong>")
			.contains("href=\"/restaurants?open=true\"")
			.doesNotContain(">Open now</span>", "Opens at", "action=\"/cart/items/");
		assertThat(row(html, "Margherita")).contains("disabled>ADD");
	}

	@Test
	void overnightHoursAreOpenPastMidnight() throws Exception {
		assertThat(squash(html("/r/night-owl"))).contains(">Open now</span>", "Closes at 3:00 AM",
				"<strong>6:00 PM – 3:00 AM</strong>");
		clock.setTo(LocalDateTime.of(2026, 10, 5, 1, 0));
		assertThat(squash(html("/r/night-owl"))).contains(">Open now</span>").doesNotContain("Closed now");
		clock.setTo(LocalDateTime.of(2026, 10, 5, 4, 0));
		assertThat(squash(html("/r/night-owl"))).contains(">Closed now</span>", "Opens at 6:00 PM");
	}

	@Test
	void equalHoursMeanOpenAllDay() throws Exception {
		assertThat(squash(html("/r/round-clock"))).contains("<strong>Open 24 hours</strong>", ">Open now</span>")
			.doesNotContain("Closes at");
	}

	// ---------------------------------------------------------------- menu

	@Test
	void theMenuShowsFilledSectionsInOrderWithStickyNavigation() throws Exception {
		String html = squash(html("/r/rassa-house"));
		assertThat(html)
			.contains("<nav class=\"rp-nav\" aria-label=\"Menu sections\">")
			.containsSubsequence("href=\"#menu-" + thalis.getId() + "\"><span>Thalis</span> <span class=\"rp-nav-count\">3</span>",
					"href=\"#menu-" + sides.getId() + "\"><span>Sides</span> <span class=\"rp-nav-count\">2</span>")
			.containsSubsequence("<section class=\"rp-section\" id=\"menu-" + thalis.getId() + "\"",
					"<section class=\"rp-section\" id=\"menu-" + sides.getId() + "\"")
			.contains("<p class=\"rp-menu-count\">5 dishes</p>")
			.doesNotContain("<span>Menu</span>", "Desserts"); // empty sections are left out
		assertThat(dishes(html)).containsExactly("Mutton Thali", "Veg Kolhapuri Thali", "Kheema Masala",
				"Pandhra Rassa", "Solkadhi");
	}

	@Test
	void dishRowsShowTagsPriceDescriptionImageAndAnAddButton() throws Exception {
		String html = squash(html("/r/rassa-house"));
		assertThat(row(html, "Mutton Thali"))
			.contains("class=\"veg-mark non-veg\"", "Non-vegetarian")
			.contains("class=\"badge badge-bestseller\"", "Bestseller")
			.contains("<span class=\"badge badge-danger\">Very spicy</span>")
			.contains("<span class=\"price\">₹420</span>")
			.contains("<p class=\"menu-item-desc\">Sukka mutton, both rassas and two bhakris.</p>")
			.contains("src=\"/placeholder/dish/mutton-thali.svg\" alt=\"Mutton Thali\" width=\"136\" height=\"136\" loading=\"lazy\"")
			.containsPattern("<form action=\"/cart/items/" + muttonThali.getId() + "\" method=\"post\">"
					+ "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/?>")
			.contains("<input type=\"hidden\" name=\"quantity\" value=\"1\">")
			.contains("<button type=\"submit\" class=\"btn btn-add\">ADD<span class=\"visually-hidden\"> Mutton Thali to cart</span>");
		assertThat(row(html, "Veg Kolhapuri Thali")).contains("class=\"veg-mark\"", "Vegetarian",
				"<span class=\"badge badge-warning\">Spicy</span>").doesNotContain("Bestseller");
		assertThat(row(html, "Pandhra Rassa")).contains("<span class=\"badge badge-warning\">Mild spice</span>",
				"₹90.50").doesNotContain("menu-item-desc");
		assertThat(row(html, "Solkadhi")).doesNotContain("spicy", "Spicy", "Mild spice");
	}

	@Test
	void soldOutDishesAreShownDisabledAndArchivedOnesAreHidden() throws Exception {
		String html = squash(html("/r/rassa-house"));
		assertThat(row(html, "Kheema Masala"))
			.contains("class=\"menu-item is-unavailable\"", "<span class=\"badge\">Sold out today</span>")
			.contains("<button type=\"button\" class=\"btn btn-add\" disabled>ADD")
			.contains("Kheema Masala, sold out")
			.doesNotContain("<form");
		assertThat(html).doesNotContain("Old Paneer Pasta", "/cart/items/" + kheema.getId());
	}

	@Test
	void pureVegOnlyHidesNonVegDishes() throws Exception {
		String html = squash(html("/r/rassa-house?veg=true"));
		assertThat(dishes(html)).containsExactly("Veg Kolhapuri Thali", "Solkadhi");
		assertThat(html)
			.contains("<input class=\"switch\" type=\"checkbox\" name=\"veg\" value=\"true\" checked=\"checked\">")
			.contains("<p class=\"rp-menu-count\">2 of 5 dishes · veg only</p>");
	}

	@Test
	void aPureVegRestaurantSaysSoInsteadOfOfferingTheToggle() throws Exception {
		assertThat(squash(html("/r/sunrise-misal"))).contains("Pure veg kitchen: every dish is vegetarian")
			.doesNotContain("name=\"veg\"");
		assertThat(squash(html("/r/rassa-house"))).contains("Pure veg only").doesNotContain("Pure veg kitchen");
	}

	@Test
	void searchFindsDishesByNameOrDescriptionAndHidesOtherSections() throws Exception {
		String html = squash(html("/r/rassa-house?q=kokum"));
		assertThat(dishes(html)).containsExactly("Solkadhi");
		assertThat(html)
			.contains("value=\"kokum\"", "<p class=\"rp-menu-count\">1 of 5 dishes match “kokum”</p>")
			.contains("href=\"#menu-" + sides.getId() + "\"")
			.doesNotContain("href=\"#menu-" + thalis.getId() + "\"");
		assertThat(dishes(html("/r/rassa-house?q=THALI"))).containsExactly("Mutton Thali", "Veg Kolhapuri Thali");
		assertThat(dishes(html("/r/rassa-house?q=thali&veg=true"))).containsExactly("Veg Kolhapuri Thali");
	}

	@Test
	void emptyResultsExplainThemselves() throws Exception {
		String noMatch = squash(html("/r/rassa-house?q=pizza"));
		assertThat(noMatch).contains("No dishes match “pizza”", "href=\"/r/rassa-house\">Show the full menu</a>")
			.doesNotContain("class=\"rp-nav\"", "class=\"rp-menu-count\"");
		assertThat(dishes(noMatch)).isEmpty();

		assertThat(squash(html("/r/night-owl?veg=true"))).contains("No vegetarian dishes here",
				"href=\"/r/night-owl\">Show the full menu</a>");
		assertThat(squash(html("/r/empty-kitchen"))).contains("No dishes listed yet",
				"href=\"/restaurants\">Browse restaurants</a>").doesNotContain("Show the full menu");
	}

	@Test
	void theMenuFormWorksWithoutJavaScriptAndRedirectsToTheCanonicalUrl() throws Exception {
		String html = squash(html("/r/rassa-house"));
		assertThat(html).contains("action=\"/r/rassa-house\" method=\"get\"", "hx-get=\"/r/rassa-house\"",
				"<noscript><button type=\"submit\" class=\"btn btn-secondary\">Search menu</button></noscript>");
		mvc.perform(at("/r/rassa-house?q=&veg=")).andExpect(redirectedUrl("/r/rassa-house"));
		mvc.perform(at("/r/rassa-house?q=%20thali%20&veg=false")).andExpect(redirectedUrl("/r/rassa-house?q=thali"));
		mvc.perform(at("/r/rassa-house?veg=maybe")).andExpect(redirectedUrl("/r/rassa-house"));
		mvc.perform(at("/r/rassa-house?veg=true&q=veg%20thali")).andExpect(redirectedUrl("/r/rassa-house?q=veg+thali&veg=true"));
		mvc.perform(at("/r/rassa-house?q=veg%20thali&veg=true")).andExpect(status().isOk());
	}

	@Test
	void htmxGetsOnlyTheMenuAndTheCanonicalUrl() throws Exception {
		String html = mvc.perform(at("/r/rassa-house?veg=true&q=").header("HX-Request", "true"))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/restaurant :: htmx-menu"))
			.andExpect(header().string("HX-Push-Url", "/r/rassa-house?veg=true"))
			.andExpect(header().string("Vary", "HX-Request"))
			.andReturn().getResponse().getContentAsString();
		assertThat(squash(html).strip())
			.startsWith("<div id=\"menu-results\" class=\"rp-menu-body\">")
			.contains("<p id=\"menu-status\" hx-swap-oob=\"innerHTML\">2 of 5 dishes · veg only</p>")
			.doesNotContain("<html", "site-header", "rp-hero");
		assertThat(dishes(html)).containsExactly("Veg Kolhapuri Thali", "Solkadhi");
	}

	@Test
	void aSignedInCustomerSeesTheSamePage() throws Exception {
		mvc.perform(at("/r/rassa-house").with(user("riya@example.com").roles("CUSTOMER")))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/restaurant"));
	}

	@Test
	void unknownAndNotApprovedRestaurantsAreNotFound() throws Exception {
		for (String slug : List.of("no-such-place", "pending-place", "suspended-place", "rejected-place")) {
			mvc.perform(at("/r/" + slug)).andExpect(status().isNotFound()).andExpect(view().name("error/404"));
			mvc.perform(at("/r/" + slug).header("HX-Request", "true")).andExpect(status().isNotFound());
		}
	}

	// ---------------------------------------------------------------- cart stepper (quantities come from T21)

	@Test
	void aDishAlreadyInTheCartShowsAQuantityStepper() throws Exception {
		String html = squash(mvc.perform(at("/probe/restaurant-with-cart").with(user("riya@example.com").roles("CUSTOMER")))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(row(html, "Mutton Thali"))
			.contains("class=\"qty\" role=\"group\" aria-label=\"Quantity of Mutton Thali\"")
			.contains("action=\"/cart/items/" + muttonThali.getId() + "\" method=\"post\"")
			.contains("name=\"quantity\" value=\"1\" aria-label=\"Remove one Mutton Thali\"")
			.contains("<span class=\"qty-value\">2</span>")
			.contains("name=\"quantity\" value=\"3\" aria-label=\"Add one more Mutton Thali\"")
			.doesNotContain("btn-add");
		assertThat(row(html, "Solkadhi")).contains("btn btn-add").doesNotContain("class=\"qty\"");
	}

	// ---------------------------------------------------------------- queries

	@Test
	void aPageLoadRunsThreeQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		html("/r/rassa-house"); // warm up
		stats.clear();
		html("/r/rassa-house");
		// restaurant with cuisines, categories, items: nothing per section or per dish
		assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
	}

	@Controller
	static class CartProbe {

		private final RestaurantMenuService menus;

		CartProbe(RestaurantMenuService menus) {
			this.menus = menus;
		}

		@GetMapping("/probe/restaurant-with-cart")
		String page(Model model) {
			MenuFilter filter = new MenuFilter();
			RestaurantMenu menu = menus.menu("rassa-house", filter);
			Long mutton = menu.sections().get(0).items().get(0).getId();
			model.addAttribute("menu", menu);
			model.addAttribute("filter", filter);
			model.addAttribute("cartQuantities", Map.of(mutton, 2));
			return "catalog/restaurant";
		}

	}

}
