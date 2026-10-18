package com.zaika.review;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.data.domain.Sort;

/** The order of a restaurant's public reviews: {@code ?sort=highest}; newest first when absent. */
public enum ReviewSort {

	NEWEST("Newest", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),

	HIGHEST("Highest rated", Sort.by(Sort.Order.desc("stars"), Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

	private final String label;

	private final Sort sort;

	ReviewSort(String label, Sort sort) {
		this.label = label;
		this.sort = sort;
	}

	/** {@code newest} / {@code highest}; null when {@code raw} names neither. */
	public static ReviewSort parse(String raw) {
		return raw == null ? null
				: Arrays.stream(values()).filter(s -> s.slug().equals(raw.strip().toLowerCase(Locale.ROOT)))
					.findFirst()
					.orElse(null);
	}

	public String slug() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String getLabel() {
		return label;
	}

	Sort sort() {
		return sort;
	}

}
