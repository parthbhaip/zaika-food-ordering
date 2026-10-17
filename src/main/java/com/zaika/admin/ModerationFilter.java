package com.zaika.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.StringJoiner;

import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Requests;
import com.zaika.delivery.RiderStatus;
import com.zaika.user.Role;

/**
 * The query of an admin list ({@code /admin/restaurants?status=&q=&page=}, {@code /admin/riders?status=...},
 * {@code /admin/users?role=...}): one enum value as a lower-case slug ({@code pending}, {@code restaurant-owner}; every
 * value when absent), search text and a 1-based page. Unknown or unreadable values fall back to the defaults, and
 * {@link #url()} is the canonical form (only non-default values, fixed order, form-encoded), so the controller can
 * redirect every other spelling to it.
 *
 * @param <S> what the chips filter by: a status or a role
 */
public final class ModerationFilter<S extends Enum<S>> {

	static final String BASE = "/admin/restaurants";

	static final String RIDERS = "/admin/riders";

	static final String USERS = "/admin/users";

	/** Same limit as the other search boxes. */
	static final int MAX_QUERY_LENGTH = 80;

	private final String base;

	private final String param;

	private final S status;

	private final String q;

	private int page;

	private ModerationFilter(String base, String param, S status, String q, int page) {
		this.base = base;
		this.param = param;
		this.status = status;
		this.q = q;
		this.page = page;
	}

	/** {@code /admin/restaurants}: the raw request parameters (each may be null). */
	static ModerationFilter<RestaurantStatus> of(String status, String q, String page) {
		return of(BASE, "status", RestaurantStatus.values(), status, q, page);
	}

	/** {@code /admin/riders}: the raw request parameters (each may be null). */
	static ModerationFilter<RiderStatus> riders(String status, String q, String page) {
		return of(RIDERS, "status", RiderStatus.values(), status, q, page);
	}

	/** {@code /admin/users}: the raw request parameters (each may be null). */
	static ModerationFilter<Role> users(String role, String q, String page) {
		return of(USERS, "role", Role.values(), role, q, page);
	}

	private static <S extends Enum<S>> ModerationFilter<S> of(String base, String param, S[] values, String status,
			String q, String page) {
		S parsed = status == null ? null
				: Arrays.stream(values).filter(s -> slug(s).equals(status.strip().toLowerCase(Locale.ROOT)))
					.findFirst()
					.orElse(null);
		return new ModerationFilter<>(base, param, parsed, Requests.searchText(q, MAX_QUERY_LENGTH),
				Math.max(1, Requests.pageNumber(page)));
	}

	/** The value the chips select, or null for every value. */
	public S getStatus() {
		return status;
	}

	/** The query parameter the chips set: {@code status} or {@code role}. */
	public String getField() {
		return param;
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

	/** The list's address without any query. */
	public String getBase() {
		return base;
	}

	/** The canonical URL of this query. */
	public String url() {
		return url(status, q, page);
	}

	/** This search with another value (or every value for null), from the first page: the filter chips. */
	public String urlFor(S other) {
		return url(other, q, 1);
	}

	/** This value without the search: "Clear search". */
	public String withoutSearch() {
		return url(status, null, 1);
	}

	/** The value as it appears in URLs: {@code pending}, {@code restaurant-owner}. */
	public static String slug(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/** How the admin pages name a value: the filter chips (and the restaurant badges). */
	public static String label(Enum<?> value) {
		return switch (value) {
			case RestaurantStatus s -> switch (s) {
				case PENDING -> "Pending review";
				case APPROVED -> "Live";
				case REJECTED -> "Rejected";
				case SUSPENDED -> "Suspended";
			};
			case RiderStatus s -> switch (s) {
				case PENDING -> "Under review";
				case APPROVED -> "Approved";
				case SUSPENDED -> "Suspended";
			};
			case Role r -> switch (r) {
				case CUSTOMER -> "Customers";
				case RESTAURANT_OWNER -> "Restaurant owners";
				case DELIVERY_PARTNER -> "Delivery partners";
				case ADMIN -> "Admins";
			};
			default -> value.name();
		};
	}

	/** One account's role: "Customer", "Restaurant owner". */
	public static String roleName(Role role) {
		return switch (role) {
			case CUSTOMER -> "Customer";
			case RESTAURANT_OWNER -> "Restaurant owner";
			case DELIVERY_PARTNER -> "Delivery partner";
			case ADMIN -> "Admin";
		};
	}

	private String url(S status, String q, int page) {
		StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
		if (status != null) {
			query.add(param + "=" + slug(status));
		}
		if (q != null) {
			query.add("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
		}
		if (page > 1) {
			query.add("page=" + page);
		}
		return base + query;
	}

}
