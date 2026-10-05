package com.zaika.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The listing query: normalisation, the canonical URL, and the LIKE pattern built from the search text. */
class RestaurantFilterTest {

	@Test
	void defaultsGiveThePlainListingUrl() {
		RestaurantFilter filter = new RestaurantFilter();
		assertThat(filter.url()).isEqualTo("/restaurants");
		assertThat(filter.isFiltered()).isFalse();
		assertThat(filter.getSortOption()).isEqualTo(RestaurantSort.RELEVANCE);
	}

	@Test
	void urlListsOnlyNonDefaultValuesInAFixedOrderFormEncoded() {
		RestaurantFilter filter = new RestaurantFilter();
		filter.setPage(3);
		filter.setSort("cost-desc");
		filter.setOpen(true);
		filter.setTop(true);
		filter.setVeg(true);
		filter.setCuisine("south-indian");
		filter.setQ("dal & rice+ghee");
		assertThat(filter.url()).isEqualTo(
				"/restaurants?q=dal+%26+rice%2Bghee&cuisine=south-indian&veg=true&top=true&open=true&sort=cost-desc&page=3");
		assertThat(filter.isFiltered()).isTrue();
	}

	@Test
	void searchTextIsStrippedBlankIsNoneAndLongTextIsCut() {
		RestaurantFilter filter = new RestaurantFilter();
		filter.setQ("   ");
		assertThat(filter.getQ()).isNull();
		filter.setQ("  misal  ");
		assertThat(filter.getQ()).isEqualTo("misal");
		filter.setQ("a".repeat(200));
		assertThat(filter.getQ()).hasSize(RestaurantFilter.MAX_QUERY_LENGTH);
	}

	@Test
	void unknownSortAndBadPagesFallBack() {
		RestaurantFilter filter = new RestaurantFilter();
		filter.setSort("spicy");
		filter.setPage(-4);
		assertThat(filter.getSortOption()).isEqualTo(RestaurantSort.RELEVANCE);
		assertThat(filter.getPage()).isEqualTo(1);
		assertThat(RestaurantSort.fromParam(null)).isEqualTo(RestaurantSort.RELEVANCE);
		assertThat(RestaurantSort.fromParam("fastest")).isEqualTo(RestaurantSort.FASTEST);
	}

	@Test
	void unknownCuisinesAreDropped() {
		RestaurantFilter filter = new RestaurantFilter();
		filter.setCuisine("sushi");
		filter.keepKnownCuisine(List.of("biryani", "pizza"));
		assertThat(filter.getCuisine()).isNull();
		filter.setCuisine("pizza");
		filter.keepKnownCuisine(List.of("biryani", "pizza"));
		assertThat(filter.getCuisine()).isEqualTo("pizza");
	}

	@Test
	void likePatternMatchesWildcardsLiterally() {
		assertThat(RestaurantBrowseService.likePattern("Misal")).isEqualTo("%misal%");
		assertThat(RestaurantBrowseService.likePattern("50%_off!")).isEqualTo("%50!%!_off!!%");
	}

}
