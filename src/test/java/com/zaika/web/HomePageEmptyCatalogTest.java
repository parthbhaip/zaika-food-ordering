package com.zaika.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.zaika.catalog.RestaurantStatus;
import com.zaika.support.CatalogFixtures;

/** The home page before any restaurant is live: one empty state, no rows, no shortcuts, the rest still there. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:homeempty;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class HomePageEmptyCatalogTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Test
	void withNoLiveRestaurantTheHomePageShowsOneEmptyState() throws Exception {
		fixtures.restaurant("waiting-room", "Waiting Room Cafe").cuisines("Cafe").rated("4.5", 10)
			.status(RestaurantStatus.PENDING).save();
		String html = mvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(view().name("home"))
			.andReturn().getResponse().getContentAsString().replaceAll("\s+", " ");
		assertThat(html).contains("Your city's best kitchens, delivered.",
				"Kolhapur's favourite kitchens are joining Zaika.",
				"<h2 class=\"empty-state-title\">No kitchens are live yet</h2>",
				"action=\"/search\"", "How Zaika works", "href=\"/partner-with-us/restaurant\"")
			.doesNotContain("kitchens open now", "Kitchens are closed right now", "Popular right now",
					"home-cuisine", "home-top-title", "home-fast-title", "home-veg-title", "home-note",
					"Waiting Room Cafe");
	}

}
