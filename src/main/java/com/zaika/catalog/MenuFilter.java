package com.zaika.catalog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.StringJoiner;

import com.zaika.common.Requests;

/**
 * The menu view of a restaurant page, bound from {@code /r/{slug}?q=&veg=}: search text within the menu and the
 * "Pure veg only" toggle. Unparseable values stay at their defaults.
 */
public class MenuFilter {

	public static final int MAX_QUERY_LENGTH = 60;

	private String q;

	private boolean veg;

	/** Search text, stripped and cut to {@link #MAX_QUERY_LENGTH}; null when blank. */
	public String getQ() {
		return q;
	}

	public void setQ(String q) {
		this.q = Requests.searchText(q, MAX_QUERY_LENGTH);
	}

	/** Vegetarian dishes only. */
	public boolean isVeg() {
		return veg;
	}

	public void setVeg(boolean veg) {
		this.veg = veg;
	}

	public boolean isFiltered() {
		return q != null || veg;
	}

	/** Whether {@code item} passes: veg when asked, and the search text in its name or description. */
	public boolean matches(MenuItem item) {
		if (veg && !item.isVeg()) {
			return false;
		}
		if (q == null) {
			return true;
		}
		String needle = q.toLowerCase(Locale.ROOT);
		return item.getName().toLowerCase(Locale.ROOT).contains(needle) || (item.getDescription() != null
				&& item.getDescription().toLowerCase(Locale.ROOT).contains(needle));
	}

	/** Canonical URL of this view of the restaurant: {@code /r/rassa-darbar?q=thali&veg=true}. */
	public String url(String slug) {
		StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
		if (q != null) {
			query.add("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
		}
		if (veg) {
			query.add("veg=true");
		}
		return "/r/" + slug + query;
	}

}
