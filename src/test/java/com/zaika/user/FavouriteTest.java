package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.NotFoundException;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;

/**
 * Favourites: the heart on a restaurant page (htmx and plain form), {@code /account/favourites}, and who may use them.
 * Own database; the test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" }) // same context as OrderPagesTest (cached, not a new one)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavouriteTest {

	@Autowired
	FavouriteService favourites;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	ZaikaUserDetailsService details;

	@Autowired
	MutableClock clock;

	@Autowired
	MockMvc mvc;

	User customer;

	Restaurant restaurant;

	@BeforeEach
	void setUp() {
		customer = orders.customer();
		restaurant = restaurant("Favourite Kitchen");
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	Restaurant restaurant(String name) {
		return fixtures.restaurant("fav-" + UUID.randomUUID().toString().substring(0, 8), name).cuisines("Kolhapuri")
			.save();
	}

	UserDetails as(User u) {
		return details.loadUserByUsername(u.getEmail());
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	MockHttpServletRequestBuilder toggle(Restaurant r) {
		return post("/account/favourites/" + r.getSlug()).with(csrf());
	}

	// ---------------------------------------------------------------- service

	@Test
	void toggleSavesAndRemovesOnlyThisCustomersFavourite() {
		assertThat(favourites.toggle(customer.getId(), restaurant.getSlug())).isTrue();
		assertThat(favourites.contains(customer.getId(), restaurant.getId())).isTrue();
		assertThat(favourites.list(customer.getId())).extracting(c -> c.name()).containsExactly("Favourite Kitchen");
		User another = orders.customer();
		assertThat(favourites.list(another.getId())).isEmpty();
		assertThat(favourites.contains(another.getId(), restaurant.getId())).isFalse();
		assertThat(favourites.toggle(customer.getId(), restaurant.getSlug())).isFalse();
		assertThat(favourites.list(customer.getId())).isEmpty();
	}

	@Test
	void onlyLiveRestaurantsCanBeAddedButASuspendedOneCanStillBeRemoved() {
		Restaurant pending = fixtures.restaurant("fav-pending-" + UUID.randomUUID().toString().substring(0, 6),
				"Pending Kitchen").status(RestaurantStatus.PENDING).save();
		assertThatThrownBy(() -> favourites.toggle(customer.getId(), pending.getSlug()))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> favourites.toggle(customer.getId(), "no-such-kitchen"))
			.isInstanceOf(NotFoundException.class);

		assertThat(favourites.toggle(customer.getId(), restaurant.getSlug())).isTrue();
		suspend(restaurant);
		assertThat(favourites.list(customer.getId())).as("hidden while suspended").isEmpty();
		assertThat(favourites.toggle(customer.getId(), restaurant.getSlug())).isFalse();
		assertThat(favourites.contains(customer.getId(), restaurant.getId())).isFalse();
	}

	void suspend(Restaurant r) {
		Restaurant stored = restaurants.findById(r.getId()).orElseThrow();
		stored.changeStatus(RestaurantStatus.SUSPENDED, "Hygiene check");
		restaurants.save(stored);
	}

	// ---------------------------------------------------------------- the heart on /r/{slug}

	@Test
	void restaurantPageShowsTheToggleToCustomersOnlyWithItsState() throws Exception {
		String page = html(get("/r/" + restaurant.getSlug()).with(user(as(customer))));
		assertThat(page).contains("id=\"restaurant-favourite\"", "action=\"/account/favourites/" + restaurant.getSlug() + "\"",
				"hx-post=\"/account/favourites/" + restaurant.getSlug() + "\"", "aria-pressed=\"false\"",
				"icons.svg#heart\"", ">Favourite</button>")
			.doesNotContain("icons.svg#heart-filled\"").doesNotContain("hx-swap-oob=\"beforeend:#toasts\"");

		favourites.toggle(customer.getId(), restaurant.getSlug());
		assertThat(html(get("/r/" + restaurant.getSlug()).with(user(as(customer)))))
			.contains("aria-pressed=\"true\"", "icons.svg#heart-filled\"", ">Favourite</button>");

		assertThat(html(get("/r/" + restaurant.getSlug()).with(anonymous()))).doesNotContain("restaurant-favourite");
		assertThat(html(get("/r/" + restaurant.getSlug())
			.with(user(details.loadUserByUsername(restaurant.getSlug() + "@fixtures.zaika.test")))))
			.doesNotContain("restaurant-favourite");
	}

	@Test
	void htmxToggleSwapsTheButtonAndAddsAToast() throws Exception {
		String added = html(toggle(restaurant).with(user(as(customer))).header("HX-Request", "true"));
		assertThat(added).contains("id=\"restaurant-favourite\"", "aria-pressed=\"true\"", "icons.svg#heart-filled\"",
				"hx-swap-oob=\"beforeend:#toasts\"", "Added to favourites.")
			.doesNotContain("<html");
		assertThat(favourites.contains(customer.getId(), restaurant.getId())).isTrue();

		String removed = html(toggle(restaurant).with(user(as(customer))).header("HX-Request", "true"));
		assertThat(removed).contains("aria-pressed=\"false\"", "Removed from favourites.");
		assertThat(favourites.contains(customer.getId(), restaurant.getId())).isFalse();
	}

	@Test
	void plainFormToggleRedirectsBackWithAFlash() throws Exception {
		mvc.perform(toggle(restaurant).with(user(as(customer))))
			.andExpect(redirectedUrl("/r/" + restaurant.getSlug()))
			.andExpect(flash().attribute("flashSuccess", "Added to favourites."));
		mvc.perform(toggle(restaurant).with(user(as(customer))).param("returnTo", "/account/favourites"))
			.andExpect(redirectedUrl("/account/favourites"))
			.andExpect(flash().attribute("flashSuccess", "Removed from favourites."));
		mvc.perform(toggle(restaurant).with(user(as(customer))).param("returnTo", "https://example.com/phish"))
			.andExpect(redirectedUrl("/r/" + restaurant.getSlug()));
	}

	// ---------------------------------------------------------------- /account/favourites

	@Test
	void favouritesPageListsLiveFavouritesNewestFirstWithRemoveButtons() throws Exception {
		Restaurant older = restaurant("Older Kitchen");
		Restaurant suspended = restaurant("Suspended Kitchen");
		favourites.toggle(customer.getId(), older.getSlug());
		favourites.toggle(customer.getId(), suspended.getSlug());
		clock.advance(java.time.Duration.ofMinutes(5));
		favourites.toggle(customer.getId(), restaurant.getSlug());
		suspend(suspended);
		favourites.toggle(orders.customer().getId(), restaurant("Someone Else's Kitchen").getSlug());

		String page = html(get("/account/favourites").with(user(as(customer))));
		assertThat(page).contains("<title>Favourites | Zaika</title>", "2 kitchens saved.",
				"href=\"/account/favourites\" aria-current=\"page\"", "class=\"restaurant-card",
				"href=\"/r/" + restaurant.getSlug() + "\"", "Kolhapuri",
				"action=\"/account/favourites/" + older.getSlug() + "\"", "name=\"returnTo\" value=\"/account/favourites\"",
				"Remove<span class=\"visually-hidden\"> Older Kitchen from favourites</span>")
			.doesNotContain("Suspended Kitchen").doesNotContain("Someone Else&#39;s Kitchen")
			.doesNotContain("No favourites yet");
		assertThat(page.indexOf("Favourite Kitchen")).as("newest first").isLessThan(page.indexOf("Older Kitchen"));
	}

	@Test
	void emptyFavouritesPageInvitesBrowsing() throws Exception {
		assertThat(html(get("/account/favourites").with(user(as(customer)))))
			.contains("No favourites yet", "href=\"/restaurants\"").doesNotContain("kitchens saved");
	}

	@Test
	void accountTabsAndHeaderMenuLinkToFavourites() throws Exception {
		assertThat(html(get("/account").with(user(as(customer)))))
			.contains("href=\"/account/favourites\"><svg class=\"icon\" aria-hidden=\"true\" focusable=\"false\"><use href=\"/img/icons.svg#heart\"></use></svg>Favourites</a>");
	}

	// ---------------------------------------------------------------- security

	@Test
	void onlySignedInCustomersCanUseFavourites() throws Exception {
		mvc.perform(get("/account/favourites").with(anonymous())).andExpect(redirectedUrl("/login"));
		mvc.perform(toggle(restaurant).with(anonymous())).andExpect(redirectedUrl("/login"));
		UserDetails owner = details.loadUserByUsername(restaurant.getSlug() + "@fixtures.zaika.test");
		mvc.perform(get("/account/favourites").with(user(owner))).andExpect(status().isForbidden());
		mvc.perform(toggle(restaurant).with(user(owner))).andExpect(status().isForbidden());
		mvc.perform(toggle(restaurant).with(user(as(orders.rider())))).andExpect(status().isForbidden());
		mvc.perform(post("/account/favourites/" + restaurant.getSlug()).with(user(as(customer))))
			.andExpect(status().isForbidden());
		mvc.perform(post("/account/favourites/no-such-kitchen").with(csrf()).with(user(as(customer))))
			.andExpect(status().isNotFound());
		assertThat(favourites.contains(customer.getId(), restaurant.getId())).isFalse();
	}

}
