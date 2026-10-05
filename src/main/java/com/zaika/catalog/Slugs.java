package com.zaika.catalog;

import java.text.Normalizer;
import java.util.Locale;

/** URL slugs: lower-case ASCII words joined by single dashes ("Kolhapuri Misal & More" -> "kolhapuri-misal-more"). */
public final class Slugs {

	/** What a stored slug looks like. */
	public static final String PATTERN = "[a-z0-9]+(-[a-z0-9]+)*";

	private Slugs() {
	}

	/** Accents are dropped, every run of other characters becomes one dash. May return "" for symbol-only input. */
	public static String slugify(String text) {
		String ascii = Normalizer.normalize(text.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
	}

}
