package com.zaika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.support.CatalogFixtures;

/**
 * The public catalog API ({@code /api/v1/cuisines}, {@code /restaurants}, {@code /restaurants/{slug}},
 * {@code /search}). Shares OrderPagesTest's context and database (Spring's context cache is full), so every fixture
 * here has "Quokka" in its name and the list tests search for it or filter by the "Quokka Fusion" cuisine. Clock:
 * Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogApiTest {

	static final String BASE = "http://localhost:8080";

	static final List<String> WOKS = List.of("quokka-wok-1", "quokka-wok-2", "quokka-wok-3", "quokka-wok-4",
			"quokka-wok-5", "quokka-wok-6", "quokka-wok-7", "quokka-wok-8", "quokka-wok-9");

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuItemRepository items;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	Restaurant rassa;

	@BeforeAll
	void createRestaurants() {
		rassa = fixtures.restaurant("quokka-rassa", "Quokka Rassa House").cuisines("Kolhapuri", "Quokka Fusion")
			.rated("4.5", 200).prep(25).costForTwo(600).minimumOrder(149)
			.dishes("Mutton Thali", "Tambda Rassa", "Quokka Special Biryani").archivedDishes("Secret Pasta").save();
		for (MenuItem item : items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(rassa.getId())) {
			if (item.getName().equals("Mutton Thali")) {
				item.setDescription("Sukka, tambda and pandhra rassa with bhakri.");
				item.setBestseller(true);
				item.setSpiceLevel(3);
			}
			if (item.getName().equals("Tambda Rassa")) {
				item.setAvailable(false);
			}
			items.save(item);
		}
		fixtures.restaurant("quokka-greens", "Quokka Green Thali").cuisines("Quokka Fusion").pureVeg()
			.rated("4.2", 100).costForTwo(400).dishes("Veg Thali").save();
		fixtures.restaurant("quokka-morning", "Quokka Morning Misal").cuisines("Street Food").pureVeg()
			.rated("4.8", 300).prep(15).costForTwo(150).hours("06:00", "10:00").dishes("Misal Pav").save();
		fixtures.restaurant("quokka-new", "Quokka New Oven").cuisines("Pizza").costForTwo(500).deliveryFee(0)
			.dishes("Farmhouse Pizza").save();
		fixtures.restaurant("quokka-paused", "Quokka Paused Pizza").cuisines("Pizza").rated("3.8", 40).paused()
			.dishes("Margherita").save();
		for (String slug : WOKS) {
			fixtures.restaurant(slug, "Quokka Wok " + slug.substring(slug.length() - 1)).cuisines("Chinese")
				.rated("3.5", 20).prep(40).costForTwo(300).dishes("Hakka Noodles").save();
		}
		fixtures.restaurant("quokka-hidden", "Quokka Hidden Cafe").cuisines("Quokka Fusion").rated("4.9", 10)
			.status(RestaurantStatus.PENDING).dishes("Misal Pav").save();
		fixtures.restaurant("quokka-closed-down", "Quokka Closed Down Dhaba").cuisines("Quokka Fusion")
			.rated("4.9", 10).status(RestaurantStatus.SUSPENDED).dishes("Quokka Special Biryani").save();
	}

	ResultActions api(String url) throws Exception {
		return mvc.perform(get(URI.create(url)));
	}

	List<String> slugs(String url) throws Exception {
		return JsonPath.read(api(url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.items[*].slug");
	}

	// ---------------------------------------------------------------- cuisines

	@Test
	void cuisinesAreListedByNameWithoutAToken() throws Exception {
		MvcResult result = api("/api/v1/cuisines").andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[?(@.slug == 'quokka-fusion')].name").value(hasItem("Quokka Fusion")))
			.andReturn();
		List<String> names = JsonPath.read(result.getResponse().getContentAsString(), "$[*].name");
		assertThat(names).isSortedAccordingTo(String::compareTo).contains("Kolhapuri", "Pizza", "Chinese");
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).as("stateless").isNull();
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	// ---------------------------------------------------------------- restaurant list

	@Test
	void restaurantsListOnlyApprovedOnesTwelveToAPageOpenOnesFirst() throws Exception {
		api("/api/v1/restaurants?q=quokka").andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(12))
			.andExpect(jsonPath("$.totalItems").value(14))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items", hasSize(12)));
		// relevance: open before closed, then the best rated; the unrated one after the 3.5 woks
		List<String> first = slugs("/api/v1/restaurants?q=quokka");
		assertThat(first).startsWith("quokka-rassa", "quokka-greens").endsWith("quokka-new");
		assertThat(first.subList(2, 11)).containsExactlyElementsOf(WOKS);
		assertThat(slugs("/api/v1/restaurants?q=quokka&page=2")).containsExactly("quokka-morning", "quokka-paused");
		api("/api/v1/restaurants?q=quokka&page=3").andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.page").value(3))
			.andExpect(jsonPath("$.totalItems").value(14));
		// pending and suspended restaurants never show, even when they match
		assertThat(slugs("/api/v1/restaurants?cuisine=quokka-fusion")).containsExactly("quokka-rassa", "quokka-greens");
	}

	@Test
	void eachListedRestaurantCarriesWhatTheCardShows() throws Exception {
		api("/api/v1/restaurants?q=quokka").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].slug").value("quokka-rassa"))
			.andExpect(jsonPath("$.items[0].name").value("Quokka Rassa House"))
			.andExpect(jsonPath("$.items[0].imageUrl").value(startsWith(BASE + "/")))
			.andExpect(jsonPath("$.items[0].cuisines", contains("Kolhapuri", "Quokka Fusion")))
			.andExpect(jsonPath("$.items[0].rating.average").value(4.5))
			.andExpect(jsonPath("$.items[0].rating.count").value(200))
			.andExpect(jsonPath("$.items[0].deliveryMinutes").value(35))
			.andExpect(jsonPath("$.items[0].costForTwo").value(600))
			.andExpect(jsonPath("$.items[0].pureVeg").value(false))
			.andExpect(jsonPath("$.items[0].offer").value(nullValue()))
			.andExpect(jsonPath("$.items[0].openNow").value(true))
			.andExpect(jsonPath("$.items[0].opensAt").value(nullValue()))
			// unrated, free delivery
			.andExpect(jsonPath("$.items[11].slug").value("quokka-new"))
			.andExpect(jsonPath("$.items[11].rating.average").value(nullValue()))
			.andExpect(jsonPath("$.items[11].rating.count").value(0))
			.andExpect(jsonPath("$.items[11].offer").value("Free delivery"));
		api("/api/v1/restaurants?q=quokka&page=2").andExpect(status().isOk())
			// closed by its hours: says when it opens
			.andExpect(jsonPath("$.items[0].openNow").value(false))
			.andExpect(jsonPath("$.items[0].opensAt").value("06:00"))
			// paused: no known reopening time
			.andExpect(jsonPath("$.items[1].openNow").value(false))
			.andExpect(jsonPath("$.items[1].opensAt").value(nullValue()));
	}

	@Test
	void filtersAndSortsMatchTheWebsiteListing() throws Exception {
		assertThat(slugs("/api/v1/restaurants?q=quokka&veg=true")).containsExactly("quokka-greens", "quokka-morning");
		assertThat(slugs("/api/v1/restaurants?q=quokka&top=true"))
			.containsExactly("quokka-rassa", "quokka-greens", "quokka-morning");
		assertThat(slugs("/api/v1/restaurants?q=quokka&top=true&sort=rating"))
			.containsExactly("quokka-morning", "quokka-rassa", "quokka-greens");
		assertThat(slugs("/api/v1/restaurants?q=quokka&veg=true&sort=cost-asc"))
			.containsExactly("quokka-morning", "quokka-greens");
		assertThat(slugs("/api/v1/restaurants?q=quokka&top=true&sort=cost-desc"))
			.containsExactly("quokka-rassa", "quokka-greens", "quokka-morning");
		assertThat(slugs("/api/v1/restaurants?q=quokka&veg=true&sort=fastest"))
			.containsExactly("quokka-morning", "quokka-greens");
		api("/api/v1/restaurants?q=quokka&open=true").andExpect(jsonPath("$.totalItems").value(12));
		assertThat(slugs("/api/v1/restaurants?q=quokka&open=true&page=1")).doesNotContain("quokka-morning",
				"quokka-paused");
		// q also matches dish names, and an explicit default is fine
		assertThat(slugs("/api/v1/restaurants?q=special%20biryani&veg=false&sort=relevance"))
			.contains("quokka-rassa")
			.doesNotContain("quokka-closed-down");
		assertThat(slugs("/api/v1/restaurants?q=no-such-quokka-dish")).isEmpty();
	}

	@Test
	void unknownFilterValuesAreA400NamingEachParameter() throws Exception {
		api("/api/v1/restaurants?sort=cheapest&page=0&cuisine=moon-food").andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Some fields need fixing. See errors for each field."))
			.andExpect(jsonPath("$.errors.sort").value("Sort by one of: relevance, rating, fastest, cost-asc, cost-desc."))
			.andExpect(jsonPath("$.errors.page").value("Pages start at 1."))
			.andExpect(jsonPath("$.errors.cuisine")
				.value("There is no cuisine \"moon-food\". GET /api/v1/cuisines lists them."));
		api("/api/v1/restaurants?veg=maybe").andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errors.veg").value("Use true or false."));
		api("/api/v1/restaurants?page=two").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.page").value("Use a whole number."));
		// empty values mean the defaults, as an HTML form would send them
		api("/api/v1/restaurants?q=quokka&sort=&page=&veg=&cuisine=").andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.totalItems").value(14));
	}

	// ---------------------------------------------------------------- restaurant detail

	@Test
	void aRestaurantComesWithItsMenuWithoutArchivedDishes() throws Exception {
		MvcResult result = api("/api/v1/restaurants/quokka-rassa").andExpect(status().isOk())
			.andExpect(jsonPath("$.slug").value("quokka-rassa"))
			.andExpect(jsonPath("$.name").value("Quokka Rassa House"))
			.andExpect(jsonPath("$.imageUrl").value(startsWith(BASE + "/")))
			.andExpect(jsonPath("$.logoUrl").value(startsWith(BASE + "/")))
			.andExpect(jsonPath("$.cuisines", contains("Kolhapuri", "Quokka Fusion")))
			.andExpect(jsonPath("$.address").value("12, Main Road"))
			.andExpect(jsonPath("$.area").value("Rajarampuri"))
			.andExpect(jsonPath("$.city").value("Kolhapur"))
			.andExpect(jsonPath("$.pincode").value("416008"))
			.andExpect(jsonPath("$.rating.average").value(4.5))
			.andExpect(jsonPath("$.rating.count").value(200))
			.andExpect(jsonPath("$.deliveryMinutes").value(35))
			.andExpect(jsonPath("$.costForTwo").value(600))
			.andExpect(jsonPath("$.minimumOrder").value(149))
			.andExpect(jsonPath("$.deliveryFee").value(30))
			.andExpect(jsonPath("$.pureVeg").value(false))
			.andExpect(jsonPath("$.opensAt").value("11:00"))
			.andExpect(jsonPath("$.closesAt").value("23:00"))
			.andExpect(jsonPath("$.openAllDay").value(false))
			.andExpect(jsonPath("$.openNow").value(true))
			.andExpect(jsonPath("$.acceptingOrders").value(true))
			.andExpect(jsonPath("$.nextOpensAt").value(nullValue()))
			.andExpect(jsonPath("$.offers[0].title").value("Free delivery above ₹499"))
			.andExpect(jsonPath("$.offers[0].detail").value("₹30 delivery fee below that"))
			.andExpect(jsonPath("$.menu", hasSize(1)))
			.andExpect(jsonPath("$.menu[0].name").value("Menu"))
			.andExpect(jsonPath("$.menu[0].items[*].name",
					contains("Mutton Thali", "Tambda Rassa", "Quokka Special Biryani")))
			.andExpect(jsonPath("$.menu[0].items[0].description").value("Sukka, tambda and pandhra rassa with bhakri."))
			.andExpect(jsonPath("$.menu[0].items[0].price").value(199))
			.andExpect(jsonPath("$.menu[0].items[0].veg").value(false))
			.andExpect(jsonPath("$.menu[0].items[0].bestseller").value(true))
			.andExpect(jsonPath("$.menu[0].items[0].spiceLevel").value(3))
			.andExpect(jsonPath("$.menu[0].items[0].available").value(true))
			.andExpect(jsonPath("$.menu[0].items[0].imageUrl").value(startsWith(BASE + "/")))
			// sold out stays on the menu, marked
			.andExpect(jsonPath("$.menu[0].items[1].available").value(false))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("Secret Pasta", "fixtures.zaika.test", "9876543210", "passwordHash", "owner");
		Number id = JsonPath.read(body, "$.menu[0].items[0].id");
		assertThat(items.findById(id.longValue())).get().extracting(MenuItem::getName).isEqualTo("Mutton Thali");
		assertThat(body).contains("\"price\":199.00");
	}

	@Test
	void aClosedRestaurantSaysWhenItOpens() throws Exception {
		api("/api/v1/restaurants/quokka-morning").andExpect(status().isOk())
			.andExpect(jsonPath("$.openNow").value(false))
			.andExpect(jsonPath("$.acceptingOrders").value(true))
			.andExpect(jsonPath("$.nextOpensAt").value("06:00"))
			.andExpect(jsonPath("$.menu[0].items[0].veg").value(true));
		api("/api/v1/restaurants/quokka-paused").andExpect(status().isOk())
			.andExpect(jsonPath("$.openNow").value(false))
			.andExpect(jsonPath("$.acceptingOrders").value(false))
			.andExpect(jsonPath("$.nextOpensAt").value(nullValue()));
		api("/api/v1/restaurants/quokka-new").andExpect(status().isOk())
			.andExpect(jsonPath("$.offers[0].title").value("Free delivery"))
			.andExpect(jsonPath("$.rating.average").value(nullValue()));
	}

	@Test
	void unknownAndUnapprovedRestaurantsAreA404Problem() throws Exception {
		for (String slug : List.of("no-such-quokka", "quokka-hidden", "quokka-closed-down")) {
			api("/api/v1/restaurants/" + slug).andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("We couldn't find that."))
				.andExpect(jsonPath("$.instance").value("/api/v1/restaurants/" + slug));
		}
	}

	// ---------------------------------------------------------------- search

	@Test
	void searchFindsRestaurantsAndDishesOfApprovedKitchensOnly() throws Exception {
		api("/api/v1/search?q=%20Quokka%20Special%20").andExpect(status().isOk())
			.andExpect(jsonPath("$.q").value("Quokka Special"))
			.andExpect(jsonPath("$.restaurantCount").value(1))
			.andExpect(jsonPath("$.restaurants[*].slug", contains("quokka-rassa")))
			.andExpect(jsonPath("$.restaurants[0].rating.average").value(4.5))
			.andExpect(jsonPath("$.dishCount").value(1))
			.andExpect(jsonPath("$.dishes", hasSize(1)))
			.andExpect(jsonPath("$.dishes[0].name").value("Quokka Special Biryani"))
			.andExpect(jsonPath("$.dishes[0].price").value(199))
			.andExpect(jsonPath("$.dishes[0].veg").value(false))
			.andExpect(jsonPath("$.dishes[0].available").value(true))
			.andExpect(jsonPath("$.dishes[0].imageUrl").value(startsWith(BASE + "/")))
			.andExpect(jsonPath("$.dishes[0].restaurant.slug").value("quokka-rassa"))
			.andExpect(jsonPath("$.dishes[0].restaurant.name").value("Quokka Rassa House"))
			.andExpect(jsonPath("$.dishes[0].restaurant.area").value("Rajarampuri"))
			.andExpect(jsonPath("$.dishes[0].restaurant.openNow").value(true));
		api("/api/v1/search?q=quokka").andExpect(status().isOk())
			.andExpect(jsonPath("$.restaurantCount").value(14))
			.andExpect(jsonPath("$.restaurants", hasSize(12)));
		api("/api/v1/search?q=no-such-quokka-dish").andExpect(status().isOk())
			.andExpect(jsonPath("$.restaurantCount").value(0))
			.andExpect(jsonPath("$.restaurants", hasSize(0)))
			.andExpect(jsonPath("$.dishCount").value(0))
			.andExpect(jsonPath("$.dishes", hasSize(0)));
		String longText = "quokka" + "x".repeat(100);
		api("/api/v1/search?q=" + longText).andExpect(status().isOk())
			.andExpect(jsonPath("$.q").value(longText.substring(0, 80)));
	}

	@Test
	void searchNeedsText() throws Exception {
		for (String url : List.of("/api/v1/search", "/api/v1/search?q=", "/api/v1/search?q=%20%20")) {
			api(url).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors.q").value("Type a dish, restaurant or cuisine to search for."));
		}
	}

	// ---------------------------------------------------------------- security and queries

	@Test
	void theCatalogIsPublicAndReadOnly() throws Exception {
		MvcResult signUp = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Quokka Fan", "email": "quokka-%s@catalog-api.zaika.test", "phone": "9822012345",
					 "password": "Kolhapur@2026", "acceptTerms": true}""".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated())
			.andReturn();
		String token = JsonPath.read(signUp.getResponse().getContentAsString(), "$.accessToken");
		// a signed-in app may call it too
		mvc.perform(get("/api/v1/restaurants/quokka-rassa").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slug").value("quokka-rassa"));
		// a token that is sent must be valid
		mvc.perform(get("/api/v1/cuisines").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
		// only reads are public
		mvc.perform(post("/api/v1/restaurants")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/restaurants/quokka-rassa").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/restaurants/quokka-rassa/secrets")).andExpect(status().isUnauthorized());
	}

	@Test
	void listAndDetailRunAFixedNumberOfQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		api("/api/v1/restaurants?q=quokka").andExpect(status().isOk()); // warm up

		stats.clear();
		api("/api/v1/restaurants?q=quokka").andExpect(status().isOk());
		assertThat(stats.getPrepareStatementCount()).as("page, count, the page's cuisines").isEqualTo(3);

		stats.clear();
		api("/api/v1/restaurants/quokka-rassa").andExpect(status().isOk());
		assertThat(stats.getPrepareStatementCount()).as("restaurant, sections, dishes, coupon offers").isEqualTo(4);

		stats.clear();
		api("/api/v1/search?q=quokka").andExpect(status().isOk());
		assertThat(stats.getPrepareStatementCount()).as("restaurant page, count, cuisines, dishes").isEqualTo(4);
	}

}
