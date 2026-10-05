package com.zaika.catalog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.StringJoiner;

/**
 * The query of the restaurant listing, bound from {@code /restaurants?q=&cuisine=&veg=&top=&open=&sort=&page=}.
 * Unparseable values are left at their defaults. {@link #url()} is the canonical form of the query: only the
 * parameters that differ from the defaults, in a fixed order, form-encoded the way a browser sends a GET form.
 */
public class RestaurantFilter {

	/** Same limit as the header search box. */
	public static final int MAX_QUERY_LENGTH = 80;

	private String q;

	private String cuisine;

	private boolean veg;

	private boolean top;

	private boolean open;

	private RestaurantSort sort = RestaurantSort.RELEVANCE;

	private int page = 1;

	/** Search text, stripped and cut to {@link #MAX_QUERY_LENGTH}; null when blank. */
	public String getQ() {
		return q;
	}

	public void setQ(String q) {
		this.q = CatalogRequests.searchText(q, MAX_QUERY_LENGTH);
	}

	/** Cuisine slug, or null for every cuisine. */
	public String getCuisine() {
		return cuisine;
	}

	public void setCuisine(String cuisine) {
		this.cuisine = cuisine == null || cuisine.isBlank() ? null : cuisine.strip();
	}

	/** Drops a cuisine slug that is not one of {@code knownSlugs}, so a stale link shows every cuisine. */
	public void keepKnownCuisine(Collection<String> knownSlugs) {
		if (cuisine != null && !knownSlugs.contains(cuisine)) {
			cuisine = null;
		}
	}

	/** Pure veg restaurants only. */
	public boolean isVeg() {
		return veg;
	}

	public void setVeg(boolean veg) {
		this.veg = veg;
	}

	/** Rated {@link RestaurantBrowseService#TOP_RATING} or better. */
	public boolean isTop() {
		return top;
	}

	public void setTop(boolean top) {
		this.top = top;
	}

	/** Open and taking orders right now. */
	public boolean isOpen() {
		return open;
	}

	public void setOpen(boolean open) {
		this.open = open;
	}

	public RestaurantSort getSortOption() {
		return sort;
	}

	/** The {@code ?sort=} value; unknown values fall back to relevance. */
	public void setSort(String sort) {
		this.sort = RestaurantSort.fromParam(sort);
	}

	/** 1-based page number. */
	public int getPage() {
		return page;
	}

	public void setPage(int page) {
		this.page = Math.max(1, page);
	}

	/** Anything narrowing the list (search or a filter); the sort and page do not count. */
	public boolean isFiltered() {
		return q != null || cuisine != null || veg || top || open;
	}

	/** Canonical URL of this query: {@code /restaurants?cuisine=biryani&open=true}. */
	public String url() {
		StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
		add(query, "q", q);
		add(query, "cuisine", cuisine);
		add(query, "veg", veg ? "true" : null);
		add(query, "top", top ? "true" : null);
		add(query, "open", open ? "true" : null);
		add(query, "sort", sort == RestaurantSort.RELEVANCE ? null : sort.getParam());
		add(query, "page", page > 1 ? String.valueOf(page) : null);
		return "/restaurants" + query;
	}

	private static void add(StringJoiner query, String name, String value) {
		if (value != null) {
			query.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
		}
	}

}
