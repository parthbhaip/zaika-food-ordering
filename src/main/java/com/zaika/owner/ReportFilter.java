package com.zaika.owner;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.springframework.format.annotation.DateTimeFormat;

import com.zaika.order.OrderStatus;

/**
 * The query of the owner's reports, bound from {@code /owner/reports?from=&to=&status=&page=}: a range of days the
 * orders were placed in (both ends included), an optional status and the table page. Unparseable values are left
 * empty and {@link #normalize(LocalDate)} fills in the defaults (the last 30 days, every status, page 1).
 * {@link #url()} is the canonical form: the dates only when they differ from the default range, in a fixed order.
 */
public class ReportFilter {

	/** Days in the default range, today included. */
	public static final int DEFAULT_DAYS = 30;

	/** The longest range a report covers (a year). */
	public static final int MAX_DAYS = 366;

	/** The statuses an owner can filter by: every order that reached the kitchen ends in one of these. */
	public static final List<OrderStatus> STATUSES = Arrays.stream(OrderStatus.values())
		.filter(s -> s != OrderStatus.PENDING_PAYMENT)
		.toList();

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate from;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate to;

	private OrderStatus status;

	private int page = 1;

	private LocalDate today;

	public LocalDate getFrom() {
		return from;
	}

	public void setFrom(LocalDate from) {
		this.from = from;
	}

	public LocalDate getTo() {
		return to;
	}

	public void setTo(LocalDate to) {
		this.to = to;
	}

	/** One status, or null for every status. */
	public OrderStatus getStatusOption() {
		return status;
	}

	/** The {@code ?status=} value, e.g. {@code ready-for-pickup}; null for every status. */
	public String getStatus() {
		return status == null ? null : slug(status);
	}

	/** Takes a status slug ({@code delivered}, {@code out-for-delivery}; any case, {@code _} for {@code -}); anything else means every status. */
	public void setStatus(String value) {
		this.status = value == null ? null
				: STATUSES.stream().filter(s -> slug(s).equals(value.strip().toLowerCase(Locale.ROOT).replace('_', '-'))).findFirst()
					.orElse(null);
	}

	/** 1-based page of the orders table. */
	public int getPage() {
		return page;
	}

	public void setPage(int page) {
		this.page = Math.max(1, page);
	}

	/**
	 * Fills in the defaults as of {@code today}: a missing end is today, a missing start is 30 days before the end,
	 * swapped ends are put in order, and a range longer than a year is cut to the year up to its end.
	 * @return true when the range had to be cut
	 */
	public boolean normalize(LocalDate today) {
		this.today = today;
		if (to == null) {
			to = from != null && from.isAfter(today) ? from : today;
		}
		if (from == null) {
			from = to.minusDays(DEFAULT_DAYS - 1);
		}
		if (from.isAfter(to)) {
			LocalDate start = to;
			to = from;
			from = start;
		}
		if (days() > MAX_DAYS) {
			from = to.minusDays(MAX_DAYS - 1);
			return true;
		}
		return false;
	}

	/** Days in the range, both ends included. */
	public long days() {
		return ChronoUnit.DAYS.between(from, to) + 1;
	}

	/** The day the default range starts on. */
	private LocalDate defaultFrom() {
		return today.minusDays(DEFAULT_DAYS - 1);
	}

	/** Canonical URL of this query: {@code /owner/reports?from=2026-09-01&to=2026-09-30&status=delivered&page=2}. */
	public String url() {
		return link("/owner/reports", from, to, status, page);
	}

	/** The same orders as a CSV file (every page). */
	public String csvUrl() {
		return link("/owner/reports/orders.csv", from, to, status, 1);
	}

	/** Quick ranges ending today (and last month), each keeping the status; the matching one is active. */
	public List<Preset> presets() {
		LocalDate firstOfMonth = today.withDayOfMonth(1);
		List<Preset> presets = new ArrayList<>();
		addPreset(presets, "Today", today, today);
		addPreset(presets, "Last 7 days", today.minusDays(6), today);
		addPreset(presets, "Last 30 days", defaultFrom(), today);
		addPreset(presets, "This month", firstOfMonth, today);
		addPreset(presets, "Last month", firstOfMonth.minusMonths(1), firstOfMonth.minusDays(1));
		return presets;
	}

	private void addPreset(List<Preset> presets, String label, LocalDate start, LocalDate end) {
		presets.add(new Preset(label, link("/owner/reports", start, end, status, 1), start.equals(from) && end.equals(to)));
	}

	private String link(String path, LocalDate start, LocalDate end, OrderStatus wanted, int number) {
		StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
		if (!(start.equals(defaultFrom()) && end.equals(today))) {
			query.add("from=" + start).add("to=" + end);
		}
		if (wanted != null) {
			query.add("status=" + slug(wanted));
		}
		if (number > 1) {
			query.add("page=" + number);
		}
		return path + query;
	}

	/** {@code READY_FOR_PICKUP} -> {@code ready-for-pickup}. */
	public static String slug(OrderStatus status) {
		return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/** A quick range link. */
	public record Preset(String label, String url, boolean active) {
	}

}
