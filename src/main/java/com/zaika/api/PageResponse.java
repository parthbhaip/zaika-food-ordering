package com.zaika.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * One page of a list. {@code page} is 1-based; a page past the end has no items but the real totals.
 *
 * @param size the page size (items per full page)
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber() + 1,
				page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

}
