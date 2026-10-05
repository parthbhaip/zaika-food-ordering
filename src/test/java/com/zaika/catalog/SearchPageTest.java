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
 * {@code /search} and the header suggestions ({@code /search/suggest}) on their own database. The test clock says
 * Sunday 19:30 IST, so Sunrise Misal (06:00-10:00) is closed.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:search;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPageTest {

	static final Pattern DISH_LINK = Pattern.compile("class=\"dish-hit-link\" href=\"([^\"]+)\">([^<]+)</a>");

	static final Pattern OPTION_LINK = Pattern.compile("role=\"option\"[^>]*href=\"([^\"]+)\"");

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuItemRepository items;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	MutableClock clock;

	Long chickenBiryaniId;

	@BeforeAll
	void createRestaurants() {
		fixtures.restaurant("tambda-house", "Tambda Rassa House").cuisines("Kolhapuri").rated("4.5", 200)
			.dishes("Mutton Thali", "Tambda Rassa").archivedDishes("Secret Biryani").save();
		Restaurant midnight = fixtures.restaurant("midnight-biryani", "Midnight Biryani").cuisines("Biryani")
			.rated("4.4", 150).hours("18:00", "03:00").dishes("Chicken Dum Biryani", "Veg Biryani").save();
		Restaurant sunrise = fixtures.restaurant("sunrise-misal", "Sunrise Misal").cuisines("Maharashtrian").pureVeg()
			.hours("06:00", "10:00").dishes("Misal Pav", "Kanda Biryani").save();
		fixtures.restaurant("wok-garden", "Wok Garden").cuisines("Chinese").dishes("Hakka Noodles").save();
		fixtures.restaurant("hidden-cafe", "Hidden Biryani Cafe").cuisines("Biryani")
			.status(RestaurantStatus.PENDING).dishes("Biryani Special").save();
		fixtures.restaurant("closed-down-dhaba", "Closed Down Dhaba").cuisines("Biryani")
			.status(RestaurantStatus.SUSPENDED).dishes("Chicken Dum Biryani").save();
		for (int i = 1; i <= 13; i++) {
			fixtures.restaurant("momo-point-" + i, "Momo Point " + i).cuisines("Tibetan").dishes("Steamed Momos").save();
		}
		String[] platters = new String[12];
		for (int i = 0; i < platters.length; i++) {
			platters[i] = "Momo Platter " + (i + 1);
		}
		fixtures.restaurant("momo-factory", "The Momo Factory").cuisines("Tibetan").dishes(platters).save();

		for (MenuItem item : items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(midnight.getId())) {
			if (item.getName().equals("Veg Biryani")) {
				item.setAvailable(false);
				items.save(item);
			}
			else {
				chickenBiryaniId = item.getId();
			}
		}
		for (MenuItem item : items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(sunrise.getId())) {
			if (item.getName().equals("Kanda Biryani")) {
				item.setBestseller(true);
				items.save(item);
			}
		}
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static MockHttpServletRequestBuilder at(String url) {
		return get(URI.create(url));
	}

	static MockHttpServletRequestBuilder htmx(String url) {
		return at(url).header("HX-Request", "true");
	}

	String html(String url) throws Exception {
		return mvc.perform(at(url))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/search"))
			.andReturn().getResponse().getContentAsString();
	}

	String suggestions(String url) throws Exception {
		return mvc.perform(htmx(url))
			.andExpect(status().isOk())
			.andExpect(view().name("catalog/search :: suggestions"))
			.andExpect(header().string("Vary", "HX-Request"))
			.andReturn().getResponse().getContentAsString();
	}

	static List<String> groups(Pattern pattern, String html, int group) {
		List<String> found = new ArrayList<>();
		Matcher m = pattern.matcher(html);
		while (m.find()) {
			found.add(m.group(group));
		}
		return found;
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	// ---------------------------------------------------------------- /search

	@Test
	void withoutASearchThePageOffersCuisinesToStartFrom() throws Exception {
		String html = html("/search");
		assertThat(html).contains("<title>Search | Zaika</title>")
			.contains("What are you craving?")
			.contains("Popular cuisines in Kolhapur")
			.contains("href=\"/search?q=Biryani\">Biryani</a>")
			.contains("href=\"/search?q=Chinese\">Chinese</a>")
			.containsPattern("<input class=\"input\" id=\"search-q\" name=\"q\" type=\"search\"[^>]*autofocus")
			.contains("href=\"/restaurants\"")
			.doesNotContain("dish-hit")
			.doesNotContain("restaurant-card-link");
	}

	@Test
	void blankOrPaddedSearchesRedirectToTheCanonicalUrl() throws Exception {
		mvc.perform(at("/search?q=")).andExpect(redirectedUrl("/search"));
		mvc.perform(at("/search?q=%20%20")).andExpect(redirectedUrl("/search"));
		mvc.perform(at("/search?q=%20chicken%20biryani%20")).andExpect(redirectedUrl("/search?q=chicken+biryani"));
		html("/search?q=chicken%20biryani"); // the canonical form (MockMvc does not decode +, Tomcat does)
	}

	@Test
	void resultsComeInTwoGroupsRestaurantsAndDishes() throws Exception {
		String html = html("/search?q=biryani");
		assertThat(html).contains("<title>Results for “biryani” | Zaika</title>")
			.contains("Results for “biryani”")
			.contains("2 restaurants and 3 dishes");
		String restaurants = html.substring(html.indexOf("id=\"search-restaurants-title\""),
				html.indexOf("id=\"search-dishes-title\""));
		String dishes = html.substring(html.indexOf("id=\"search-dishes-title\""));

		// A name or cuisine match, and a restaurant that serves a matching dish. Never pending, suspended or archived.
		assertThat(RestaurantListingPageTest.slugs(restaurants)).containsExactly("midnight-biryani", "sunrise-misal");
		// Dishes from kitchens taking orders now come first: Sunrise Misal's bestseller waits until it opens
		assertThat(groups(DISH_LINK, dishes, 2)).containsExactly("Chicken Dum Biryani", "Veg Biryani",
				"Kanda Biryani");
		assertThat(dishes).doesNotContain("Secret Biryani").doesNotContain("Biryani Special")
			.doesNotContain("Closed Down Dhaba");
	}

	@Test
	void dishesFromKitchensTakingOrdersNowComeFirst() throws Exception {
		clock.setTo(LocalDateTime.of(2026, 10, 5, 7, 0)); // Monday 7 AM: Sunrise Misal open, Midnight Biryani closed
		String html = html("/search?q=biryani");
		assertThat(groups(DISH_LINK, html.substring(html.indexOf("id=\"search-dishes-title\"")), 2))
			.containsExactly("Kanda Biryani", "Chicken Dum Biryani", "Veg Biryani");
	}

	@Test
	void eachDishShowsItsRestaurantAndOpensItOnTheMenu() throws Exception {
		String html = squash(html("/search?q=chicken"));
		assertThat(groups(DISH_LINK, html, 1))
			.containsExactly("/r/midnight-biryani?q=Chicken+Dum+Biryani#dish-" + chickenBiryaniId);
		String hit = html.substring(html.indexOf("<li class=\"dish-hit\""), html.indexOf("</li>"));
		assertThat(hit).contains("Midnight Biryani, Rajarampuri")
			.contains("₹199")
			.contains("Non-vegetarian")
			.contains("loading=\"lazy\"")
			.doesNotContain("Restaurant closed now");
		// The link lands on a working, filtered menu
		mvc.perform(at("/r/midnight-biryani?q=Chicken%20Dum%20Biryani")).andExpect(status().isOk());
	}

	@Test
	void soldOutBestsellerAndClosedStatesShowOnDishes() throws Exception {
		String html = squash(html("/search?q=biryani"));
		String kanda = html.substring(html.lastIndexOf("<li", html.indexOf(">Kanda Biryani<")),
				html.indexOf("</li>", html.indexOf(">Kanda Biryani<")));
		assertThat(kanda).contains("Bestseller").contains("Restaurant closed now").contains("Vegetarian");
		String veg = html.substring(html.lastIndexOf("<li", html.indexOf(">Veg Biryani<")),
				html.indexOf("</li>", html.indexOf(">Veg Biryani<")));
		assertThat(veg).contains("dish-hit is-unavailable").contains("Sold out today");
	}

	@Test
	void aCuisineMatchFindsRestaurantsEvenWithoutMatchingDishes() throws Exception {
		String html = html("/search?q=chinese");
		assertThat(RestaurantListingPageTest.slugs(html)).containsExactly("wok-garden");
		assertThat(html).contains("1 restaurant and 0 dishes").contains("No dish names match “chinese”");
	}

	@Test
	void bigResultSetsLinkToTheFullListingAndSayHowManyDishesAreHidden() throws Exception {
		String html = html("/search?q=momo");
		assertThat(html).contains("14 restaurants and 25 dishes");
		assertThat(RestaurantListingPageTest.slugs(html)).hasSize(RestaurantBrowseService.PAGE_SIZE);
		assertThat(html).contains("href=\"/restaurants?q=momo\">See all 14</a>");
		assertThat(groups(DISH_LINK, html, 2)).hasSize(SearchService.DISH_LIMIT);
		assertThat(html).contains("Showing the top 24 of 25 dishes.");
	}

	@Test
	void noMatchesShowsAFriendlyEmptyStateWithWaysOut() throws Exception {
		String html = html("/search?q=sushi");
		assertThat(html).contains("No matches for “sushi”")
			.contains("Check the spelling")
			.contains("Or pick a cuisine")
			.contains("href=\"/search?q=Kolhapuri\">Kolhapuri</a>")
			.contains("href=\"/restaurants\"")
			.doesNotContain("search-restaurants-title");
	}

	@Test
	void likeWildcardsAreMatchedLiterally() throws Exception {
		assertThat(html("/search?q=%25")).contains("No matches for “%”");
		assertThat(html("/search?q=_")).contains("No matches for “_”");
	}

	@Test
	void signedInCustomersSearchToo() throws Exception {
		String html = mvc.perform(at("/search?q=misal").with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Misal Pav");
	}

	@Test
	void theSearchPageRunsAFixedNumberOfQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		html("/search?q=momo"); // warm up
		stats.clear();
		html("/search?q=momo");
		// restaurant page + count + its cuisines, dishes + count: no query per card or per dish
		assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
	}

	// ---------------------------------------------------------------- /search/suggest

	@Test
	void suggestionsListRestaurantsFirstThenDishesWithTheMatchInBold() throws Exception {
		String html = squash(suggestions("/search/suggest?q=bi"));
		assertThat(groups(OPTION_LINK, html, 1)).containsExactly("/r/midnight-biryani",
				"/r/midnight-biryani?q=Chicken+Dum+Biryani#dish-" + chickenBiryaniId,
				"/r/midnight-biryani?q=Veg+Biryani#dish-" + idOf("Veg Biryani"),
				"/r/sunrise-misal?q=Kanda+Biryani#dish-" + idOf("Kanda Biryani"), "/search?q=bi");
		assertThat(html).contains("Midnight <mark>Bi</mark>ryani")
			.contains("Restaurant · Rajarampuri")
			.contains("Kanda <mark>Bi</mark>ryani")
			.contains("Dish at Sunrise Misal")
			.contains("See all results for “bi”")
			.contains("id=\"header-suggest-0\"")
			.contains("id=\"header-suggest-4\"")
			.contains("aria-selected=\"false\"")
			.contains("tabindex=\"-1\"")
			.doesNotContain("Hidden Biryani Cafe")
			.doesNotContain("Closed Down Dhaba")
			.doesNotContain("Secret Biryani");
		assertThat(html).contains("<p id=\"header-suggest-status\" hx-swap-oob=\"innerHTML\">4 suggestions. Use the up and down arrows to choose one.</p>");
	}

	@Test
	void suggestionsStopAtFive() throws Exception {
		String html = suggestions("/search/suggest?q=momo");
		List<String> links = groups(OPTION_LINK, html, 1);
		assertThat(links).hasSize(SearchService.SUGGESTION_LIMIT + 1).endsWith("/search?q=momo");
		assertThat(links.subList(0, 5)).allMatch(link -> link.startsWith("/r/"));
		assertThat(html).contains("5 suggestions.");
	}

	@Test
	void aCuisineMatchIsSuggestedWithoutBold() throws Exception {
		String html = squash(suggestions("/search/suggest?q=chinese"));
		assertThat(groups(OPTION_LINK, html, 1)).containsExactly("/r/wok-garden", "/search?q=chinese");
		assertThat(html).contains("<span class=\"suggest-label\">Wok Garden</span>").doesNotContain("<mark>");
	}

	@Test
	void tooShortOrUnmatchedTextSuggestsNothingOrJustTheSearch() throws Exception {
		String one = suggestions("/search/suggest?q=b");
		assertThat(one).doesNotContain("role=\"option\"")
			.contains("<p id=\"header-suggest-status\" hx-swap-oob=\"innerHTML\"></p>");
		assertThat(suggestions("/search/suggest")).doesNotContain("role=\"option\"");

		String none = suggestions("/search/suggest?q=sushi");
		assertThat(groups(OPTION_LINK, none, 1)).containsExactly("/search?q=sushi");
		assertThat(none).contains("Search for “sushi”").contains("No quick matches. Press Enter to search.");
	}

	@Test
	void userTextIsEscapedInSuggestions() throws Exception {
		String html = suggestions("/search/suggest?q=%3Cb%3Ex");
		assertThat(html).contains("&lt;b&gt;x").doesNotContain("<b>x");
	}

	@Test
	void suggestWithoutHtmxGoesToTheFullSearch() throws Exception {
		mvc.perform(at("/search/suggest?q=%20bi%20")).andExpect(redirectedUrl("/search?q=bi"));
		mvc.perform(at("/search/suggest")).andExpect(redirectedUrl("/search"));
	}

	@Test
	void suggestionsTakeAtMostTwoQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		suggestions("/search/suggest?q=bi"); // warm up
		stats.clear();
		suggestions("/search/suggest?q=bi");
		assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
	}

	// ---------------------------------------------------------------- header box

	@Test
	void theHeaderSearchBoxIsAnAccessibleTypeAheadCombobox() throws Exception {
		String html = squash(html("/search?q=misal"));
		assertThat(html).contains("<div class=\"input-group search-combo\" data-suggest>")
			.containsPattern("<input class=\"input\" id=\"header-search-q\" name=\"q\" type=\"search\" maxlength=\"80\""
					+ " autocomplete=\"off\" role=\"combobox\" aria-autocomplete=\"list\" aria-expanded=\"false\""
					+ " aria-controls=\"header-suggest\" hx-get=\"/search/suggest\""
					+ " hx-trigger=\"input changed delay:250ms, search\" hx-target=\"#header-suggest\""
					+ " hx-sync=\"this:replace\"[^>]*value=\"misal\">")
			.contains("<div id=\"header-suggest\" class=\"search-suggest\" role=\"listbox\" aria-label=\"Search suggestions\"></div>")
			.contains("<p id=\"header-suggest-status\" class=\"visually-hidden\" role=\"status\"></p>")
			.contains("<script defer src=\"/js/search-suggest.js\"></script>")
			.doesNotContain("<script>");
	}

	@Test
	void theKeyboardScriptIsServedAndSmall() throws Exception {
		String js = mvc.perform(at("/js/search-suggest.js"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(js.lines().count()).isLessThan(60);
		assertThat(js).contains("ArrowDown").contains("ArrowUp").contains("Escape").contains("aria-activedescendant")
			.contains("htmx:afterSettle");
	}

	@Test
	void suggestionLabelsSplitAroundTheFirstMatch() {
		SearchService.Suggestion s = SearchService.Suggestion.of(SearchService.Suggestion.Kind.DISH, "Veg Biryani",
				"BIR", "Dish at X", "/img.svg", "/r/x", true);
		assertThat(s.before()).isEqualTo("Veg ");
		assertThat(s.match()).isEqualTo("Bir");
		assertThat(s.after()).isEqualTo("yani");
		assertThat(s.label()).isEqualTo("Veg Biryani");
		SearchService.Suggestion none = SearchService.Suggestion.of(SearchService.Suggestion.Kind.RESTAURANT,
				"Wok Garden", "chinese", "Restaurant", "/img.svg", "/r/wok", false);
		assertThat(none.before()).isEqualTo("Wok Garden");
		assertThat(none.match()).isEmpty();
	}

	Long idOf(String dishName) {
		return items.findAll().stream().filter(i -> i.getName().equals(dishName)).findFirst().orElseThrow().getId();
	}

}
