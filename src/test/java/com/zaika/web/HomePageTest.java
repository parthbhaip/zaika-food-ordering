package com.zaika.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.zaika.catalog.RestaurantStatus;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;

/**
 * The home page {@code /} on its own database. The test clock says Sunday 19:30 IST: Sunrise Misal (06:00-10:00) is
 * closed and Wok Garden is not taking orders, so five of the seven live kitchens are open.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:home;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomePageTest {

	static final Pattern CARD_LINK = Pattern.compile("class=\"restaurant-card-link\" href=\"/r/([^\"]+)\"");

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
		fixtures.restaurant("rassa-darbar", "Rassa Darbar").cuisines("Kolhapuri").rated("4.6", 300).prep(25).save();
		fixtures.restaurant("noor-biryani", "Noor Biryani").cuisines("Biryani").rated("4.4", 150).prep(15)
			.hours("18:00", "03:00").save();
		fixtures.restaurant("sunrise-misal", "Sunrise Misal").cuisines("Maharashtrian").pureVeg().rated("4.8", 90)
			.prep(10).hours("06:00", "10:00").save();
		fixtures.restaurant("thali-house", "Thali House").cuisines("Maharashtrian").pureVeg().rated("4.2", 80)
			.prep(30).save();
		fixtures.restaurant("dosa-corner", "Dosa Corner").cuisines("South Indian").pureVeg().rated("3.9", 60)
			.prep(10).save();
		fixtures.restaurant("wok-garden", "Wok Garden").cuisines("Chinese").prep(5).paused().save();
		fixtures.restaurant("pizza-yard", "Pizza Yard").cuisines("Pizza").rated("4.1", 20).prep(18).save();
		fixtures.restaurant("hidden-cafe", "Hidden Cafe").cuisines("Cafe").pureVeg().rated("5.0", 10).prep(5)
			.status(RestaurantStatus.PENDING).save();
		fixtures.restaurant("closed-dhaba", "Closed Dhaba").cuisines("Punjabi").rated("4.9", 400).prep(5)
			.status(RestaurantStatus.SUSPENDED).save();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	String html(MockMvc mvc) throws Exception {
		return mvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(view().name("home"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	String html() throws Exception {
		return html(mvc);
	}

	/** The restaurant slugs in the row whose heading has this id, in page order; empty when the row is absent. */
	static List<String> row(String html, String titleId) {
		int start = html.indexOf("aria-labelledby=\"" + titleId + "\"");
		if (start < 0) {
			return List.of();
		}
		Matcher m = CARD_LINK.matcher(html.substring(start, html.indexOf("</section>", start)));
		List<String> slugs = new ArrayList<>();
		while (m.find()) {
			slugs.add(m.group(1));
		}
		return slugs;
	}

	@Test
	void heroHasTheHeadlineTheLiveCountsAndASearchForm() throws Exception {
		String html = html();
		assertThat(html).contains("<title>Food delivery in Kolhapur | Zaika</title>",
				"<h1 id=\"home-title\">Your city's best kitchens, delivered.</h1>",
				"<span class=\"badge badge-success badge-dot\">5 kitchens open now</span>",
				"Order from 7 local kitchens and follow your food to the door.")
			.containsPattern("<form class=\"search-form home-search\" role=\"search\" aria-label=\"Search Zaika\" action=\"/search\" method=\"get\">")
			.contains("<label class=\"visually-hidden\" for=\"home-q\">Search restaurants and dishes</label>",
					"id=\"home-q\" name=\"q\" type=\"search\" maxlength=\"80\"",
					"<button type=\"submit\" class=\"btn btn-primary\">Find food</button>",
					"href=\"/search?q=Misal\"", "href=\"/search?q=Tambda%20Rassa\"")
			.doesNotContain("Kitchens are closed right now", "No kitchens are live yet")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
			.doesNotContainPattern("\\son[a-z]+=");
	}

	@Test
	void theHeroArtIsDecorationAndQuotesTheRows() throws Exception {
		assertThat(html()).contains("<div class=\"home-hero-art\" aria-hidden=\"true\">",
				"<strong>Rassa Darbar</strong><span>Top rated · 4.6 out of 5</span>",
				"<strong>20-25 min</strong><span>Fastest now · Dosa Corner</span>");
	}

	@Test
	void heroSearchLandsOnTheSearchPage() throws Exception {
		String results = mvc.perform(get(URI.create("/search?q=Rassa"))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(results).contains("href=\"/r/rassa-darbar\"");
	}

	@Test
	void cuisineChipsLinkToTheFilteredListingForCuisinesThatAreLive() throws Exception {
		String html = html();
		Matcher m = Pattern.compile("class=\"chip home-cuisine\" href=\"([^\"]+)\"").matcher(html);
		List<String> links = new ArrayList<>();
		while (m.find()) {
			links.add(m.group(1));
		}
		// by name; Cafe and Punjabi belong only to the pending and the suspended restaurant
		assertThat(links).containsExactly("/restaurants?cuisine=biryani", "/restaurants?cuisine=chinese",
				"/restaurants?cuisine=kolhapuri", "/restaurants?cuisine=maharashtrian", "/restaurants?cuisine=pizza",
				"/restaurants?cuisine=south-indian");
		assertThat(html).contains("<span class=\"home-cuisine-mark\" aria-hidden=\"true\">K</span><span>Kolhapuri</span>");
		for (String link : links) {
			String listing = mvc.perform(get(URI.create(link))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			assertThat(listing).contains("restaurant-card-link");
		}
	}

	@Test
	void topRatedShowsRatedFourPlusOpenKitchensFirst() throws Exception {
		// Sunrise Misal (4.8) is closed, so the four open 4.0+ kitchens fill the row; Dosa Corner (3.9) and the
		// unrated Wok Garden never qualify; pending and suspended restaurants never appear anywhere
		assertThat(row(html(), "home-top-title")).containsExactly("rassa-darbar", "noor-biryani", "thali-house",
				"pizza-yard");
	}

	@Test
	void fastestDeliveryShowsOpenKitchensQuickestFirst() throws Exception {
		// Wok Garden (5 min prep) is paused and Sunrise Misal closed
		assertThat(row(html(), "home-fast-title")).containsExactly("dosa-corner", "noor-biryani", "pizza-yard",
				"rassa-darbar");
	}

	@Test
	void pureVegPicksShowOnlyPureVegKitchens() throws Exception {
		assertThat(row(html(), "home-veg-title")).containsExactly("thali-house", "dosa-corner", "sunrise-misal");
	}

	@Test
	void rowsAreHeadedAndLinkToTheirFullListing() throws Exception {
		String html = html();
		assertThat(html).contains("<h2 id=\"home-top-title\" class=\"section-title\">Top rated</h2>",
				"<h2 id=\"home-fast-title\" class=\"section-title\">Fastest delivery</h2>",
				"<h2 id=\"home-veg-title\" class=\"section-title\">Pure veg picks</h2>",
				"Favourites across the city, rated 4.0 and up by people who ordered.",
				"Closed now · Opens at 6:00 AM", "Pure veg");
		Matcher m = Pattern.compile("class=\"btn btn-ghost btn-sm home-see-all\" href=\"([^\"]+)\">See all<span class=\"visually-hidden\">: ([^<]+)</span>")
			.matcher(html);
		List<String> links = new ArrayList<>();
		while (m.find()) {
			links.add(m.group(1) + " " + m.group(2));
		}
		assertThat(links).containsExactly("/restaurants?top=true Top rated",
				"/restaurants?open=true&amp;sort=fastest Fastest delivery", "/restaurants?veg=true Pure veg picks");
		// each is the listing's canonical URL, so it opens directly instead of redirecting
		for (String url : List.of("/restaurants?top=true", "/restaurants?open=true&sort=fastest",
				"/restaurants?veg=true")) {
			mvc.perform(get(URI.create(url))).andExpect(status().isOk());
		}
	}

	@Test
	void howItWorksHasThreeStepsAndThePartnerBandLinksBothWays() throws Exception {
		String html = html();
		assertThat(html).contains("<h2 id=\"home-how-title\" class=\"section-title\">How Zaika works</h2>",
				"<h3>Pick a kitchen</h3>", "<h3>Pay your way</h3>", "<h3>Track it to your door</h3>",
				"<h2 id=\"home-partner-title\" class=\"section-title\">Grow with Zaika</h2>",
				"<h3>List your restaurant</h3>", "href=\"/partner-with-us/restaurant\">Register your restaurant</a>",
				"<h3>Ride with Zaika</h3>", "href=\"/partner-with-us/rider\">Become a delivery partner</a>");
		assertThat(html.split("class=\"feature\"", -1)).hasSize(4);
	}

	@Test
	void lateAtNightTheFastestRowDisappearsAndTheHeroSaysKitchensAreClosed() throws Exception {
		clock.setTo(LocalDateTime.of(2026, 10, 5, 4, 0)); // after Noor Biryani's 03:00 close, before Sunrise Misal
		String html = html();
		assertThat(html).contains("<span class=\"badge badge-warning badge-dot\">Kitchens are closed right now</span>")
			.doesNotContain("kitchens open now", "home-fast-title", "Fastest now");
		assertThat(row(html, "home-top-title")).containsExactly("sunrise-misal", "rassa-darbar", "noor-biryani",
				"thali-house");
		assertThat(row(html, "home-veg-title")).containsExactly("sunrise-misal", "thali-house", "dosa-corner");
	}

	@Test
	void signedInCustomersGetTheHomePageToo() throws Exception {
		String html = mvc.perform(get("/").with(user("customer@zaika.test").roles("CUSTOMER")))
			.andExpect(status().isOk()).andExpect(view().name("home"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Your city's best kitchens, delivered.");
	}

	@Test
	void aNewCustomerLandsHereAfterSignUpAndSeesTheWelcomeToast() throws Exception {
		MvcResult signUp = mvc.perform(post("/register").with(csrf())
				.param("name", "Meera Kulkarni")
				.param("email", "meera@home-test.zaika.test")
				.param("phone", "9876501234")
				.param("password", "Masala2026")
				.param("confirmPassword", "Masala2026")
				.param("acceptTerms", "true"))
			.andExpect(redirectedUrl("/"))
			.andReturn();
		String html = mvc.perform(get("/").session((MockHttpSession) signUp.getRequest().getSession())
				.flashAttrs(signUp.getFlashMap()))
			.andExpect(status().isOk()).andExpect(view().name("home"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("toast-success", "Welcome to Zaika, Meera! Your account is ready.",
				"Your city's best kitchens, delivered.");
	}

	@Test
	void theHomePageRunsAFixedNumberOfQueries() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		html(); // warm up
		stats.clear();
		html();
		// three rows, their cuisines, two counts and the cuisine shortcuts: nothing per card
		assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(7);
	}

}
