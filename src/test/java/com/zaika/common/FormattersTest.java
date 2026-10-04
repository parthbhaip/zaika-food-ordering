package com.zaika.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FormattersTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 19, 30);

	private final Formatters fmt = new Formatters(Clock.fixed(NOW.atZone(IST).toInstant(), IST));

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(delimiter = '|', textBlock = """
			123456.50   | ₹1,23,456.50
			0           | ₹0.00
			0.00        | ₹0.00
			7           | ₹7.00
			99.9        | ₹99.90
			999         | ₹999.00
			1000        | ₹1,000.00
			12345       | ₹12,345.00
			100000      | ₹1,00,000.00
			1234567.89  | ₹12,34,567.89
			10000000    | ₹1,00,00,000.00
			-80         | -₹80.00
			-123456.5   | -₹1,23,456.50
			""")
	void moneyUsesRupeeSymbolAndIndianGrouping(String amount, String expected) {
		assertThat(fmt.money(new BigDecimal(amount))).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(delimiter = '|', textBlock = """
			1.005    | ₹1.01
			1.004    | ₹1.00
			2.345    | ₹2.35
			999.995  | ₹1,000.00
			-0.004   | ₹0.00
			-1.005   | -₹1.01
			""")
	void moneyRoundsHalfUpToTwoDecimals(String amount, String expected) {
		assertThat(fmt.money(new BigDecimal(amount))).isEqualTo(expected);
	}

	@Test
	void moneyAcceptsScientificNotationWithoutPrintingIt() {
		assertThat(fmt.money(new BigDecimal("1E+5"))).isEqualTo("₹1,00,000.00");
	}

	@Test
	void nullsFormatAsEmptyText() {
		assertThat(fmt.money(null)).isEmpty();
		assertThat(fmt.time(null)).isEmpty();
		assertThat(fmt.date(null)).isEmpty();
		assertThat(fmt.dateTime(null)).isEmpty();
		assertThat(fmt.relative(null)).isEmpty();
	}

	@Test
	void timesAndDatesUseTwelveHourIndianStyle() {
		assertThat(fmt.time(LocalDateTime.of(2026, 10, 4, 19, 5))).isEqualTo("7:05 PM");
		assertThat(fmt.time(LocalDateTime.of(2026, 10, 4, 0, 30))).isEqualTo("12:30 AM");
		assertThat(fmt.time(LocalDateTime.of(2026, 10, 4, 12, 0))).isEqualTo("12:00 PM");
		assertThat(fmt.date(LocalDateTime.of(2026, 9, 14, 8, 0))).isEqualTo("14 Sep 2026");
		assertThat(fmt.dateTime(LocalDateTime.of(2026, 10, 4, 9, 41))).isEqualTo("4 Oct 2026, 9:41 AM");
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(delimiter = '|', textBlock = """
			2026-10-04T19:30:00 | just now
			2026-10-04T19:29:31 | just now
			2026-10-04T19:29:00 | 1 min ago
			2026-10-04T19:25:00 | 5 min ago
			2026-10-04T18:31:00 | 59 min ago
			2026-10-04T18:30:00 | 1 hour ago
			2026-10-04T16:10:00 | 3 hours ago
			2026-10-03T19:31:00 | 23 hours ago
			2026-10-03T19:30:00 | yesterday
			2026-10-02T23:59:00 | 2 days ago
			2026-09-28T08:00:00 | 6 days ago
			2026-09-27T19:30:00 | 27 Sep 2026
			2025-12-31T10:00:00 | 31 Dec 2025
			2026-10-04T19:42:00 | in 12 min
			2026-10-04T21:30:00 | in 2 hours
			2026-10-04T20:30:00 | in 1 hour
			2026-10-06T10:00:00 | 6 Oct 2026
			""")
	void relativeTimeIsMeasuredFromTheAppClock(LocalDateTime t, String expected) {
		assertThat(fmt.relative(t)).isEqualTo(expected);
	}

}
