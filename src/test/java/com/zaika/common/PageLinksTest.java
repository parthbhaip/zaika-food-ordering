package com.zaika.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageLinksTest {

	private final PageLinks pages = new PageLinks();

	/** "_" marks a gap. */
	@ParameterizedTest(name = "page {1} of {0} -> {2}")
	@CsvSource(delimiter = '|', textBlock = """
			1  | 1  | 1
			3  | 2  | 1 2 3
			12 | 1  | 1 2 _ 12
			12 | 3  | 1 2 3 4 _ 12
			12 | 4  | 1 2 3 4 5 _ 12
			12 | 6  | 1 _ 5 6 7 _ 12
			12 | 9  | 1 _ 8 9 10 11 12
			12 | 12 | 1 _ 11 12
			5  | 3  | 1 2 3 4 5
			""")
	void showsFirstLastAndNeighboursWithGaps(int totalPages, int current, String expected) {
		int size = 10;
		var page = new PageImpl<>(List.of("x"), PageRequest.of(current - 1, size), (long) totalPages * size);
		List<String> shown = pages.numbers(page).stream().map(n -> n == null ? "_" : n.toString()).toList();
		assertThat(shown).isEqualTo(Arrays.asList(expected.split(" ")));
	}

}
