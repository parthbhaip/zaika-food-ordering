package com.zaika.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

import com.zaika.config.SeedPhotoCredits;
import com.zaika.config.SeedPhotoCredits.Entry;
import com.zaika.support.CatalogFixtures;

/**
 * {@code /photo-credits} lists every bundled seed photo for anonymous visitors, the footer links to it, and a seeded
 * restaurant or dish shows its bundled photo instead of the generated placeholder. Own database: it adds a restaurant
 * with a seeded slug.
 */
@SpringBootTest(
		properties = "spring.datasource.url=jdbc:h2:mem:photocredits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoCreditsPageTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SeedPhotoCredits credits;

	@Autowired
	CatalogFixtures fixtures;

	@BeforeAll
	void createSeededRestaurant() {
		fixtures.restaurant("rassa-darbar", "Rassa Darbar").cuisines("Kolhapuri").dishes("Mutton Thali").save();
	}

	String html(String url) throws Exception {
		return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	static String escape(String text) {
		return HtmlUtils.htmlEscape(text, "UTF-8");
	}

	@Test
	void anonymousVisitorsSeeEveryCredit() throws Exception {
		String html = mvc.perform(get("/photo-credits"))
			.andExpect(status().isOk())
			.andExpect(view().name("pages/photo-credits"))
			.andReturn().getResponse().getContentAsString();

		assertThat(html).contains("<title>Photo credits | Zaika</title>", "Wikimedia Commons",
				"<h2 id=\"credits-restaurants\" class=\"section-title\">Restaurants</h2>",
				"<h2 id=\"credits-dishes\" class=\"section-title\">Dishes</h2>");
		List<Entry> all = new ArrayList<>(credits.restaurants());
		all.addAll(credits.dishes());
		assertThat(html.split("class=\"credit-card\"", -1)).hasSize(all.size() + 1);
		for (Entry e : all) {
			assertThat(html).contains("src=\"/img/seed/" + e.credit().file() + "\" alt=\"" + escape(e.name()) + "\"")
				.contains(escape(e.credit().author()), escape(e.credit().license()))
				.contains("href=\"" + escape(e.credit().licenseUrl()) + "\" rel=\"license noopener\"")
				.contains("href=\"" + escape(e.credit().sourceUrl())
						+ "\" rel=\"noopener\" target=\"_blank\">View on Wikimedia Commons");
		}
		assertThat(html).contains(
				"src=\"/img/seed/cover/rassa-darbar.jpg\" alt=\"Rassa Darbar\" width=\"96\" height=\"60\" loading=\"lazy\"");
		long generic = all.stream().filter(e -> e.credit().generic()).count();
		assertThat(generic).isPositive();
		assertThat(html.split(">Similar dish<", -1)).hasSize((int) generic + 1);
	}

	@Test
	void theFooterLinksToThePhotoCredits() throws Exception {
		assertThat(html("/about")).contains("<a href=\"/photo-credits\">Photo credits</a>");
	}

	@Test
	void aSeededRestaurantCardAndMenuRowShowTheBundledPhoto() throws Exception {
		assertThat(html("/restaurants")).contains("/img/seed/cover/rassa-darbar.jpg")
			.doesNotContain("/placeholder/restaurant/rassa-darbar.svg");
		assertThat(html("/r/rassa-darbar")).contains("src=\"/img/seed/dish/mutton-thali.jpg\" alt=\"Mutton Thali\"")
			.doesNotContain("/placeholder/dish/mutton-thali.svg");
	}

}
