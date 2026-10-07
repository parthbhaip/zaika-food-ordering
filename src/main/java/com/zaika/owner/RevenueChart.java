package com.zaika.owner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard's 7-day revenue bar chart, worked out in Java: one bar per day (six days ago to today) in an SVG
 * {@code viewBox} of {@link #WIDTH} x {@link #HEIGHT} that the template stretches to its box, plus the y-axis ticks
 * (top, half, 0: in reading order for the labels) as percentages for the HTML labels beside it. The top of the scale is a round amount (1, 2 or 5 x a
 * power of ten, at least ₹100) at or above the best day, so the half tick is round too.
 */
public record RevenueChart(List<Bar> bars, List<Tick> ticks, BigDecimal total) {

	public static final int WIDTH = 700;

	public static final int HEIGHT = 200;

	public static final int DAYS = 7;

	/** Lowest top of the scale, so a slow week still gets a sensible axis. */
	static final BigDecimal MIN_SCALE = new BigDecimal("100");

	/** A bar never drawn lower than this when its day sold something, so a small day stays visible. */
	static final double MIN_BAR = 2;

	private static final double SLOT = (double) WIDTH / DAYS;

	private static final double BAR_WIDTH = Math.round(SLOT * 0.56);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH);

	/** One day. {@code label} is "Today" or "Mon", {@code date} "4 Oct", {@code longLabel} "Sunday 4 Oct". */
	public record Bar(LocalDate day, String label, String date, String longLabel, BigDecimal amount, boolean today,
			double x, double y, double width, double height) {
	}

	/** A y-axis line: its amount and how far up the plot it sits (0 to 100). */
	public record Tick(BigDecimal amount, int percent) {
	}

	/** The chart for the seven days ending {@code today}; days missing from {@code revenueByDay} sold nothing. */
	public static RevenueChart of(LocalDate today, Map<LocalDate, BigDecimal> revenueByDay) {
		List<BigDecimal> amounts = new ArrayList<>();
		for (int i = DAYS - 1; i >= 0; i--) {
			amounts.add(revenueByDay.getOrDefault(today.minusDays(i), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
		}
		BigDecimal best = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::max);
		BigDecimal scale = niceCeiling(best);
		List<Bar> bars = new ArrayList<>();
		for (int i = 0; i < DAYS; i++) {
			LocalDate day = today.minusDays(DAYS - 1 - i);
			BigDecimal amount = amounts.get(i);
			double height = amount.signum() == 0 ? 0
					: Math.max(MIN_BAR, round(amount.doubleValue() / scale.doubleValue() * HEIGHT));
			boolean isToday = day.equals(today);
			bars.add(new Bar(day, isToday ? "Today" : DAY.format(day), DATE.format(day), LONG.format(day), amount,
					isToday, round(i * SLOT + (SLOT - BAR_WIDTH) / 2), round(HEIGHT - height), BAR_WIDTH, height));
		}
		BigDecimal half = scale.divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
		List<Tick> ticks = List.of(new Tick(scale, 100), new Tick(half, 50), new Tick(BigDecimal.ZERO, 0));
		BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		return new RevenueChart(List.copyOf(bars), ticks, total);
	}

	/** Nothing sold in the whole week. */
	public boolean isEmpty() {
		return total.signum() == 0;
	}

	public int getWidth() {
		return WIDTH;
	}

	public int getHeight() {
		return HEIGHT;
	}

	/** The smallest of 1, 2, 5 x 10^n at or above {@code amount}, and never below {@link #MIN_SCALE}. */
	static BigDecimal niceCeiling(BigDecimal amount) {
		if (amount.compareTo(MIN_SCALE) <= 0) {
			return MIN_SCALE;
		}
		BigDecimal power = BigDecimal.ONE.scaleByPowerOfTen(amount.precision() - amount.scale() - 1);
		for (int step : new int[] { 1, 2, 5, 10 }) {
			BigDecimal candidate = power.multiply(BigDecimal.valueOf(step));
			if (candidate.compareTo(amount) >= 0) {
				return candidate.setScale(0, RoundingMode.UNNECESSARY);
			}
		}
		throw new IllegalStateException("unreachable: 10 x 10^n is always above " + amount);
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

}
