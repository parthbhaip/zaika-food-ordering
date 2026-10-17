package com.zaika.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.StringJoiner;

import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Requests;

/**
 * The query of {@code /admin/restaurants?status=&q=&page=}: a status as a lower-case slug ({@code pending}; any status
 * when absent), search text and a 1-based page. Unknown or unreadable values fall back to the defaults, and
 * {@link #url()} is the canonical form (only non-default values, fixed order, form-encoded), so the controller can
 * redirect every other spelling to it.
 */
public final class ModerationFilter {

	static final String BASE = "/admin/restaurants";

	/** Same limit as the other search boxes. */
	static final int MAX_QUERY_LENGTH = 80;

	private final RestaurantStatus status;

	private final String q;

	private int page;

	private ModerationFilter(RestaurantStatus status, String q, int page) {
		this.status = status;
		this.q = q;
		this.page = page;
	}

	/** Reads the raw request parameters (each may be null). */
	static ModerationFilter of(String status, String q, String page) {
		return new ModerationFilter(parse(status), Requests.searchText(q, MAX_QUERY_LENGTH),
				Math.max(1, Requests.pageNumber(page)));
	}

	private static RestaurantStatus parse(String slug) {
		return slug == null ? null
				: Arrays.stream(RestaurantStatus.values())
					.filter(s -> s.name().equalsIgnoreCase(slug.strip()))
					.findFirst()
					.orElse(null);
	}

	/** The status shown, or null for every status. */
	public RestaurantStatus getStatus() {
		return status;
	}

	/** Search text, stripped and cut to {@link #MAX_QUERY_LENGTH}; null when blank. */
	public String getQ() {
		return q;
	}

	/** 1-based. */
	public int getPage() {
		return page;
	}

	void setPage(int page) {
		this.page = Math.max(1, page);
	}

	/** The canonical URL of this query. */
	public String url() {
		return url(status, q, page);
	}

	/** This search in another status (or every status for null), from the first page: the filter chips. */
	public String urlFor(RestaurantStatus other) {
		return url(other, q, 1);
	}

	/** This status without the search: "Clear search". */
	public String withoutSearch() {
		return url(status, null, 1);
	}

	/** The status as it appears in URLs ({@code pending}). */
	public static String slug(RestaurantStatus status) {
		return status.name().toLowerCase(Locale.ROOT);
	}

	/** How the admin pages name a status: the filter chips and badges. */
	public static String label(RestaurantStatus status) {
		return switch (status) {
			case PENDING -> "Pending review";
			case APPROVED -> "Live";
			case REJECTED -> "Rejected";
			case SUSPENDED -> "Suspended";
		};
	}

	private static String url(RestaurantStatus status, String q, int page) {
		StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
		if (status != null) {
			query.add("status=" + slug(status));
		}
		if (q != null) {
			query.add("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
		}
		if (page > 1) {
			query.add("page=" + page);
		}
		return BASE + query;
	}

}
