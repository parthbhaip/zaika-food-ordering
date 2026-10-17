package com.zaika.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;

/**
 * One page of an admin list plus how many rows each chip value has over the whole table, whatever the search (every
 * value present, zero when none).
 */
public record Queue<T, S extends Enum<S>>(Page<T> page, Map<S, Long> counts) {

	/** {@code found} may miss values that have no rows. */
	static <T, S extends Enum<S>> Queue<T, S> of(Page<T> page, S[] values, Map<S, Long> found) {
		Map<S, Long> counts = new LinkedHashMap<>();
		for (S value : values) {
			counts.put(value, found.getOrDefault(value, 0L));
		}
		return new Queue<>(page, counts);
	}

	/** Rows with this value, zero when none. */
	public long count(S value) {
		return counts.get(value);
	}

	public long total() {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}

}
