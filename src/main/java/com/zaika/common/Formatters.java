package com.zaika.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Display formatting for templates, as the bean {@code fmt}: {@code ${@fmt.money(order.total)}},
 * {@code ${@fmt.time(order.placedAt)}}, {@code ${@fmt.relative(review.createdAt)}}. All times are app-zone
 * (IST) {@link LocalDateTime}s. A null value formats as an empty string so a template never prints "null".
 */
@Component("fmt")
public class Formatters {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

	private final Clock clock;

	public Formatters(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Rupees with Indian digit grouping and two decimals, rounded half-up: {@code ₹1,23,456.50}, {@code -₹80.00}.
	 * Done by hand because {@link java.text.DecimalFormat} cannot group 3-then-2 (it prints ₹123,456.50 for en-IN).
	 */
	public String money(BigDecimal amount) {
		if (amount == null) {
			return "";
		}
		BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
		String plain = rounded.abs().toPlainString();
		int dot = plain.indexOf('.');
		return (rounded.signum() < 0 ? "-₹" : "₹") + groupIndian(plain.substring(0, dot)) + plain.substring(dot);
	}

	/**
	 * Menu-style price: whole rupees drop the paise ({@code ₹120}, {@code ₹1,240}), anything else keeps two
	 * decimals ({@code ₹99.50}). Bills and totals use {@link #money}.
	 */
	public String price(BigDecimal amount) {
		String money = money(amount);
		return money.endsWith(".00") ? money.substring(0, money.length() - 3) : money;
	}

	/** A whole number with Indian digit grouping, e.g. a rating count: {@code 1,23,456}. */
	public String count(long n) {
		String digits = Long.toString(n);
		return n < 0 ? "-" + groupIndian(digits.substring(1)) : groupIndian(digits);
	}

	/** Average rating with one decimal, rounded half-up: {@code 4.5}. */
	public String rating(BigDecimal average) {
		return average == null ? "" : average.setScale(1, RoundingMode.HALF_UP).toPlainString();
	}

	/** {@code 7:05 PM}. */
	public String time(LocalDateTime t) {
		return t == null ? "" : TIME.format(t);
	}

	/** {@code 6:00 PM}, e.g. a restaurant's opening time. */
	public String timeOfDay(LocalTime t) {
		return t == null ? "" : TIME.format(t);
	}

	/** The current year on the app clock, for the footer copyright. */
	public int year() {
		return Year.now(clock).getValue();
	}

	/** {@code 4 Oct 2026}. */
	public String date(LocalDateTime t) {
		return t == null ? "" : DATE.format(t);
	}

	/** {@code 4 Oct 2026, 7:05 PM}. */
	public String dateTime(LocalDateTime t) {
		return t == null ? "" : DATE_TIME.format(t);
	}

	/**
	 * How long ago (or until) {@code t}, relative to the app clock: "just now", "5 min ago", "3 hours ago",
	 * "yesterday", "4 days ago", then the plain date from a week back. Future times read "in 12 min", "in 2 hours".
	 */
	public String relative(LocalDateTime t) {
		if (t == null) {
			return "";
		}
		LocalDateTime now = LocalDateTime.now(clock);
		Duration elapsed = Duration.between(t, now);
		boolean future = elapsed.isNegative();
		long minutes = elapsed.abs().toMinutes();
		if (minutes < 1) {
			return "just now";
		}
		if (minutes < 60) {
			return future ? "in " + minutes + " min" : minutes + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			String span = hours + (hours == 1 ? " hour" : " hours");
			return future ? "in " + span : span + " ago";
		}
		if (future) {
			return date(t);
		}
		long days = ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate());
		if (days == 1) {
			return "yesterday";
		}
		return days < 7 ? days + " days ago" : date(t);
	}

	/** 1234567 -> 12,34,567: the last three digits, then groups of two. */
	private static String groupIndian(String digits) {
		int len = digits.length();
		if (len <= 3) {
			return digits;
		}
		StringBuilder out = new StringBuilder(len + len / 2);
		String head = digits.substring(0, len - 3);
		for (int i = 0; i < head.length(); i++) {
			if (i > 0 && (head.length() - i) % 2 == 0) {
				out.append(',');
			}
			out.append(head.charAt(i));
		}
		return out.append(',').append(digits, len - 3, len).toString();
	}

}
