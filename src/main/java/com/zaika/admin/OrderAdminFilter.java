package com.zaika.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import com.zaika.catalog.RestaurantRepository.Choice;
import com.zaika.common.Requests;
import com.zaika.order.OrderStatus;

/**
 * The query of the admin's order list ({@code /admin/orders?q=&status=&restaurant=&from=&to=&page=}): part of an order
 * code, one status (lower-case slug, {@code out-for-delivery}), one restaurant (slug), a range of checkout days (both
 * ends included, either may be left open) and a 1-based page. Unknown, unreadable or out-of-range values are dropped,
 * swapped dates are put in order, and {@link #url()} is the canonical form (only what is set, fixed order), so the
 * controller can redirect every other spelling to it.
 */
public final class OrderAdminFilter {

	static final String BASE = "/admin/orders";

	/** Longest code text kept ("ZK-26-000123" is 12). */
	static final int MAX_CODE_LENGTH = 20;

	/** Dates before this, or more than a year after today, count as missing. */
	static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

	private final String q;

	private final OrderStatus status;

	private final Choice restaurant;

	private final LocalDate from;

	private final LocalDate to;

	private int page;

	private OrderAdminFilter(String q, OrderStatus status, Choice restaurant, LocalDate from, LocalDate to, int page) {
		this.q = q;
		this.status = status;
		this.restaurant = restaurant;
		this.from = from;
		this.to = to;
		this.page = page;
	}

	/** From the raw request parameters (each may be null); {@code restaurants} are the known ones. */
	static OrderAdminFilter of(String q, String status, String restaurant, String from, String to, String page,
			List<Choice> restaurants, LocalDate today) {
		OrderStatus parsed = status == null ? null
				: Arrays.stream(OrderStatus.values())
					.filter(s -> ModerationFilter.slug(s).equals(status.strip().toLowerCase(Locale.ROOT)))
					.findFirst()
					.orElse(null);
		Choice kitchen = restaurant == null ? null
				: restaurants.stream().filter(r -> r.slug().equals(restaurant.strip())).findFirst().orElse(null);
		LocalDate start = date(from, today);
		LocalDate end = date(to, today);
		if (start != null && end != null && start.isAfter(end)) {
			LocalDate swap = start;
			start = end;
			end = swap;
		}
		return new OrderAdminFilter(Requests.searchText(q, MAX_CODE_LENGTH), parsed, kitchen, start, end,
				Math.max(1, Requests.pageNumber(page)));
	}

	private static LocalDate date(String raw, LocalDate today) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			LocalDate date = LocalDate.parse(raw.strip());
			return date.isBefore(EARLIEST) || date.isAfter(today.plusYears(1)) ? null : date;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	/** Code text, stripped and cut to {@link #MAX_CODE_LENGTH}; null when blank. */
	public String getQ() {
		return q;
	}

	/** One status, or null for every status. */
	public OrderStatus getStatus() {
		return status;
	}

	/** One restaurant, or null for all of them. */
	public Choice getRestaurant() {
		return restaurant;
	}

	/** First checkout day, or null for no lower bound. */
	public LocalDate getFrom() {
		return from;
	}

	/** Last checkout day (included), or null for no upper bound. */
	public LocalDate getTo() {
		return to;
	}

	/** 1-based. */
	public int getPage() {
		return page;
	}

	void setPage(int page) {
		this.page = Math.max(1, page);
	}

	/** Start of the first day, or null. */
	LocalDateTime fromTime() {
		return from == null ? null : from.atStartOfDay();
	}

	/** Start of the day after the last one (exclusive), or null. */
	LocalDateTime toTime() {
		return to == null ? null : to.plusDays(1).atStartOfDay();
	}

	/** Whether anything narrows the list (the empty state and "Clear filters" depend on it). */
	public boolean isFiltered() {
		return q != null || status != null || restaurant != null || from != null || to != null;
	}

	/** The list without any filter. */
	public String getBase() {
		return BASE;
	}

	/** The canonical URL of this query. */
	public String url() {
		StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
		if (q != null) {
			query.add("q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
		}
		if (status != null) {
			query.add("status=" + ModerationFilter.slug(status));
		}
		if (restaurant != null) {
			query.add("restaurant=" + restaurant.slug());
		}
		if (from != null) {
			query.add("from=" + from);
		}
		if (to != null) {
			query.add("to=" + to);
		}
		if (page > 1) {
			query.add("page=" + page);
		}
		return BASE + query;
	}

}
