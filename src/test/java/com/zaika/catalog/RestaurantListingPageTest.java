package com.zaika.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;

/**
 * {@code /restaurants} on its own database: 13 approved restaurants (one closed by its hours, one paused, one open
 * overnight, one unrated) plus a pending and a suspended one that must never show. The test clock says Sunday
 * 19:30 IST unless a test moves it.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:listing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestaurantListingPageTest {

	static final Pattern CARD_SLUG = Pattern.compile("class=\"restaurant-card-link\" href=\"/r/([a-z0-9-]+)\"");

	static final List<String> WOKS = List.of("wok-corner-1", "wok-corner-2", "wok-corner-3", "wok-corner-4",
			"wok-corner-5", "wok-corner-6", "wok-corner-7");

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MutableClock clock;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@BeforeAll
	void createRestaurants() {
		fixtures.restaurant("tambda-house", "Tambda Rassa House").cuisines("Kolhapuri").rated("4.5", 200).prep(25)
			.costForTwo(600).dishes("Mutton Thali", "Tambda Rassa").archivedDishes("Secret Pasta").save();
		fixtures.restaurant("green-leaf", "Green Leaf Thali").cuisines("Maharashtrian").pureVeg().rated("4.2", 100)
			.costForTwo(400).hours("11:00", "22:30").dishes("Veg Thali").save();
		fixtures.restaurant("sunrise-misal", "Sunrise Misal").cuisines("Maharashtrian", "Street Food").pureVeg()
			.rated("4.8", 300).prep(15).costForTwo(150).hours("06:00", "10:00").dishes("Misal Pav").save();
		fixtures.restaurant("midnight-biryani", "Midnight Biryani").cuisines("Biryani").rated("4.4", 150).prep(30)
			.costForTwo(700).hours("18:00", "03:00").dishes("Chicken Dum Biryani").save();
		fixtures.restaurant("fresh-oven", "Fresh Oven").cuisines("Pizza").costForTwo(500).deliveryFee(0)
			.dishes("Farmhouse Pizza").save();
		fixtures.restaurant("paused-pizza", "Paused Pizza Co").cuisines("Pizza").rated("3.8", 40).costForTwo(450).paused()
			.dishes("Margherita").save();
		for (int i = 1; i <= 7; i++) {
			fixtures.restaurant("wok-corner-" + i, "Wok Corner " + i).cuisines("Chinese").rated("3.5", 20).prep(40)
				.costForTwo(300).dishes("Hakka Noodles").save();
		}
		fixtures.restaurant("hidden-cafe", "Hidden Cafe").cuisines("Street Food").rated("4.9", 10)
			.status(RestaurantStatus.PENDING).dishes("Misal Pav").save();
		fixtures.restaurant("closed-down-dhaba", "Closed Down Dhaba").cuisines("Biryani").rated("4.9", 10)
			.status(RestaurantStatus.SUSPENDED).dishes("Chicken Dum Biryani").save();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/**
	 * A GET of {@code url} exactly as written (already encoded, like a browser sends it). MockMvc's
	 * {@code get(String)} would treat it as a URI template and encode the {@code %} signs again.
	 */
	static MockHttpServletRequestBuilder at(String url) {
		return get(URI.create(url));
	}

	String html(String url) throws Exception {
		return mvc.perform(at(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/** Restaurant slugs in the order the cards appear. */
	static List<String> slugs(String html) {
		List<String> slugs = new ArrayList<>();
		Matcher m = CARD_SLUG.matcher(html);
		while (m.find()) {
			slugs.add(m.group(1));
		}
		return slugs;
	}

	List<String> slugsAt(String url) throws Exception {
		return slugs(html(url));
	}

	/** The markup of one restaurant's card. */
	static String card(String html, String slug) {
		int link = html.indexOf("href=\"/r/" + slug + "\"");
		assertThat(link).as("card for %s", slug).isPositive();
		return html.substring(html.lastIndexOf("<article", link), html.indexOf("</article>", link));
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	// ---------------------------------------------------------------- listing and cards

	@Test
	void anonymousVisitorsSeeOnlyApprovedRestaurantsTwelveToAPage() throws Exception {
		String html = mvc.perform(at("/restaurants"))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/restaurants"))
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("<title>Restaurants in Kolhapur | Zaika</title>", "13 restaurants")
			.doesNotContain("Hidden Cafe", "Closed Down Dhaba");
		assertThat(slugs(html)).hasSize(RestaurantBrowseService.PAGE_SIZE);
		// relevance: open restaurants by rating, then the closed ones
		assertThat(slugs(html).subList(0, 3)).containsExactly("tambda-house", "midnight-biryani", "green-leaf");
		assertThat(slugs(html).getLast()).isEqualTo("sunrise-misal");
		assertThat(slugsAt("/restaurants?page=2")).containsExactly("paused-pizza");
	}

	@Test
	void cardsShowCuisinesRatingDeliveryTimeCostAndPureVeg() throws Exception {
		String html = html("/restaurants");
		String tambda = squash(card(html, "tambda-house"));
		assertThat(tambda).contains("Tambda Rassa House", "Kolhapuri", "4.5", "out of 5, 200 ratings", "35-40 min",
				"₹600 for two", "/placeholder/restaurant/tambda-house.svg", "loading=\"lazy\"")
			.doesNotContain("Pure veg", "is-closed");
		assertThat(squash(card(html, "green-leaf"))).contains("Pure veg", "30-35 min", "₹400 for two");
		assertThat(squash(card(html, "sunrise-misal"))).contains("Maharashtrian, Street Food");
		String fresh = squash(card(html, "fresh-oven"));
		assertThat(fresh).contains("New", "no ratings yet", "Free delivery");
	}

	@Test
	void closedRestaurantsAreGreyedWithTheirNextOpeningTime() throws Exception {
		String sunrise = squash(card(html("/restaurants"), "sunrise-misal"));
		assertThat(sunrise).contains("is-closed", "Closed now · Opens at 6:00 AM");

		// paused by the owner: closed, but no reopening time to promise
		String paused = squash(card(html("/restaurants?page=2"), "paused-pizza"));
		assertThat(paused).contains("is-closed", "Closed now").doesNotContain("Opens at");
	}

	@Test
	void signedInUsersOfAnyRoleCanBrowse() throws Exception {
		for (String role : List.of("CUSTOMER", "RESTAURANT_OWNER", "DELIVERY_PARTNER", "ADMIN")) {
			mvc.perform(get("/restaurants").with(user("someone").roles(role))).andExpect(status().isOk());
		}
	}

	// ---------------------------------------------------------------- filters

	@Test
	void cuisineFilterShowsOnlyThatCuisine() throws Exception {
		assertThat(slugsAt("/restaurants?cuisine=biryani")).containsExactly("midnight-biryani");
		assertThat(slugsAt("/restaurants?cuisine=pizza")).containsExactly("fresh-oven", "paused-pizza");
	}

	@Test
	void pureVegFilter() throws Exception {
		assertThat(slugsAt("/restaurants?veg=true")).containsExactly("green-leaf", "sunrise-misal");
	}

	@Test
	void topRatedFilterNeedsFourStarsAndAtLeastOneRating() throws Exception {
		assertThat(slugsAt("/restaurants?top=true")).containsExactly("tambda-house", "midnight-biryani", "green-leaf",
				"sunrise-misal");
	}

	@Test
	void openNowFilterDropsClosedAndPausedRestaurants() throws Exception {
		List<String> open = slugsAt("/restaurants?open=true");
		assertThat(open).hasSize(11).doesNotContain("sunrise-misal", "paused-pizza");
		assertThat(html("/restaurants?open=true")).contains("11 restaurants");
	}

	@Test
	void openNowUnderstandsHoursPastMidnight() throws Exception {
		clock.setTo(LocalDateTime.of(2026, 10, 5, 1, 0)); // Monday 1 AM
		assertThat(slugsAt("/restaurants?open=true")).containsExactly("midnight-biryani");
		clock.setTo(LocalDateTime.of(2026, 10, 5, 7, 0));
		assertThat(slugsAt("/restaurants?open=true")).containsExactly("sunrise-misal");
	}

	@Test
	void filtersCombine() throws Exception {
		assertThat(slugsAt("/restaurants?cuisine=maharashtrian&veg=true&open=true")).containsExactly("green-leaf");
	}

	@Test
	void filtersThatMatchNothingShowAnEmptyStateWithAWayOut() throws Exception {
		String html = squash(html("/restaurants?cuisine=biryani&veg=true"));
		assertThat(slugs(html)).isEmpty();
		assertThat(html).contains("0 restaurants", "No restaurants match these filters", "href=\"/restaurants\"",
				"Clear filters");
	}

	@Test
	void theFormShowsTheActiveFilters() throws Exception {
		String html = html("/restaurants?cuisine=biryani&veg=true&sort=rating");
		assertThat(html).containsPattern("<input[^>]*value=\"biryani\"[^>]*checked")
			.doesNotContainPattern("<input[^>]*value=\"pizza\"[^>]*checked")
			.containsPattern("<input[^>]*name=\"veg\"[^>]*checked")
			.doesNotContainPattern("<input[^>]*name=\"open\"[^>]*checked")
			.containsPattern("<option[^>]*value=\"rating\"[^>]*selected");
	}

	// ---------------------------------------------------------------- search

	@Test
	void searchFindsRestaurantsByNameOrDish() throws Exception {
		assertThat(slugsAt("/restaurants?q=biryani")).containsExactly("midnight-biryani");
		assertThat(slugsAt("/restaurants?q=hakka")).containsExactlyElementsOf(WOKS);
		// the pending restaurant also sells misal pav, but is not listed
		assertThat(slugsAt("/restaurants?q=misal%20pav")).containsExactly("sunrise-misal");
		assertThat(slugsAt("/restaurants?q=TAMBDA")).containsExactly("tambda-house");
		assertThat(slugsAt("/restaurants?q=chinese")).containsExactlyElementsOf(WOKS); // a cuisine name (T17)
	}

	@Test
	void restaurantNameMatchesComeBeforeDishMatches() throws Exception {
		// Tambda Rassa House is rated higher but only sells a thali; Green Leaf Thali has it in its name
		String html = html("/restaurants?q=thali");
		assertThat(slugs(html)).containsExactly("green-leaf", "tambda-house");
		assertThat(html).contains("2 restaurants for “thali”");
	}

	@Test
	void archivedDishesAreNotSearchable() throws Exception {
		assertThat(slugsAt("/restaurants?q=secret%20pasta")).isEmpty();
	}

	@Test
	void likeWildcardsInTheSearchAreMatchedLiterally() throws Exception {
		assertThat(slugsAt("/restaurants?q=%25")).isEmpty();
		assertThat(slugsAt("/restaurants?q=_")).isEmpty();
	}

	@Test
	void aSearchWithNoMatchSaysSo() throws Exception {
		String html = squash(html("/restaurants?q=sushi"));
		assertThat(html).contains("0 restaurants for “sushi”", "Nothing matches “sushi”", "Clear search and filters");
	}

	// ---------------------------------------------------------------- sorting

	@Test
	void sortByRating() throws Exception {
		assertThat(slugsAt("/restaurants?sort=rating").subList(0, 5)).containsExactly("sunrise-misal", "tambda-house",
				"midnight-biryani", "green-leaf", "paused-pizza");
	}

	@Test
	void sortByDeliveryTime() throws Exception {
		assertThat(slugsAt("/restaurants?sort=fastest").subList(0, 4)).containsExactly("sunrise-misal", "green-leaf",
				"paused-pizza", "fresh-oven");
	}

	@Test
	void sortByCostBothWays() throws Exception {
		assertThat(slugsAt("/restaurants?sort=cost-asc").subList(0, 2)).containsExactly("sunrise-misal",
				"wok-corner-1");
		assertThat(slugsAt("/restaurants?sort=cost-desc").subList(0, 5)).containsExactly("midnight-biryani",
				"tambda-house", "fresh-oven", "paused-pizza", "green-leaf");
	}

	// ---------------------------------------------------------------- pages and canonical URLs

	@Test
	void pagerLinksKeepTheFilters() throws Exception {
		String first = html("/restaurants?sort=rating");
		assertThat(first).contains("href=\"/restaurants?sort=rating&amp;page=2\"");
		String second = html("/restaurants?sort=rating&page=2");
		assertThat(slugs(second)).containsExactly("fresh-oven");
		assertThat(second).contains("href=\"/restaurants?sort=rating\"", "aria-current=\"page\"");
	}

	@Test
	void aPagePastTheEndRedirectsToTheLastPage() throws Exception {
		mvc.perform(at("/restaurants?page=9")).andExpect(redirectedUrl("/restaurants?page=2"));
		mvc.perform(at("/restaurants?cuisine=biryani&page=3")).andExpect(redirectedUrl("/restaurants?cuisine=biryani"));
		mvc.perform(at("/restaurants?q=sushi&page=2")).andExpect(redirectedUrl("/restaurants?q=sushi"));
	}

	@Test
	void emptyDefaultAndUnparseableParametersRedirectToTheCanonicalUrl() throws Exception {
		mvc.perform(at("/restaurants?q=&cuisine=&sort=relevance")).andExpect(redirectedUrl("/restaurants"));
		mvc.perform(at("/restaurants?veg=maybe&page=two&sort=spicy")).andExpect(redirectedUrl("/restaurants"));
		mvc.perform(at("/restaurants?cuisine=sushi")).andExpect(redirectedUrl("/restaurants"));
		mvc.perform(at("/restaurants?page=1")).andExpect(redirectedUrl("/restaurants"));
		mvc.perform(at("/restaurants?sort=rating&veg=true&q=%20thali%20"))
			.andExpect(redirectedUrl("/restaurants?q=thali&veg=true&sort=rating"));
	}

	// ---------------------------------------------------------------- htmx

	static MockHttpServletRequestBuilder htmx(String url) {
		return at(url).header("HX-Request", "true");
	}

	@Test
	void htmxRequestsGetOnlyTheResultsAndTheCanonicalUrlToPush() throws Exception {
		String html = mvc.perform(htmx("/restaurants?q=&cuisine=biryani&sort=relevance"))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/restaurants :: htmx-results"))
			.andExpect(header().string("HX-Push-Url", "/restaurants?cuisine=biryani"))
			.andExpect(header().stringValues("Vary", org.hamcrest.Matchers.hasItem("HX-Request")))
			.andReturn().getResponse().getContentAsString();

		assertThat(html).doesNotContain("<html", "site-header", "listing-filters")
			.contains("id=\"restaurant-results\"", "hx-swap-oob=\"innerHTML\"");
		assertThat(slugs(html)).containsExactly("midnight-biryani");
		assertThat(squash(html)).containsPattern("<p id=\"listing-status\" hx-swap-oob=\"innerHTML\">\\s*1 restaurant\\s*</p>");
	}

	@Test
	void htmxPagePastTheEndShowsTheLastPage() throws Exception {
		String html = mvc.perform(htmx("/restaurants?page=9"))
			.andExpect(status().isOk())
			.andExpect(header().string("HX-Push-Url", "/restaurants?page=2"))
			.andReturn().getResponse().getContentAsString();
		assertThat(slugs(html)).containsExactly("paused-pizza");
	}

	@Test
	void htmxHistoryRestoreGetsTheFullPage() throws Exception {
		String html = mvc.perform(htmx("/restaurants?cuisine=biryani").header("HX-History-Restore-Request", "true"))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/restaurants"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("<html", "listing-filters");
	}

	@Test
	void theFormWorksWithoutJavaScriptAndUpgradesWithHtmx() throws Exception {
		String html = squash(html("/restaurants"));
		assertThat(html).contains("<form class=\"listing-filters\"", "action=\"/restaurants\"", "method=\"get\"",
				"hx-get=\"/restaurants\"", "hx-target=\"#restaurant-results\"", "hx-push-url=\"true\"",
				"<noscript><div class=\"listing-apply\"><button type=\"submit\"", "id=\"listing-status\"",
				"role=\"status\"", "hx-boost=\"true\"")
			.doesNotContain("<script>", " onclick=", "hx-on");
		// only the pager is boosted; the card links are ordinary links to the restaurant pages
		String grid = html.substring(html.indexOf("restaurant-grid"), html.indexOf("listing-pager"));
		assertThat(grid).contains("href=\"/r/tambda-house\"").doesNotContain("hx-");
		assertThat(html("/restaurants?cuisine=biryani")).doesNotContain("hx-boost", "listing-pager");
		// the cuisine chips come from the cuisine table, by name
		assertThat(html).containsSubsequence("All cuisines", "Biryani", "Chinese", "Kolhapuri", "Maharashtrian", "Pizza",
				"Street Food");
	}

	// ---------------------------------------------------------------- queries

	@Test
	void aPageLoadRunsAFixedNumberOfQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		html("/restaurants"); // warm up
		stats.clear();
		html("/restaurants");
		// cuisine chips, the page, the count, the page's cuisines: no query per card
		assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
	}

}
