package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.zaika.owner.OwnerDashboardService.Today;
import com.zaika.owner.RevenueChart.Bar;
import com.zaika.owner.RevenueChart.Tick;

/** T29: the 7-day revenue chart's geometry and scale, and today's average order value. */
class RevenueChartTest {

	static final LocalDate TODAY = LocalDate.of(2026, 10, 4); // a Sunday

	@Test
	void sevenDaysEndingTodayOldestFirst() {
		RevenueChart chart = RevenueChart.of(TODAY, Map.of());
		assertThat(chart.bars()).extracting(Bar::day)
			.containsExactly(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30),
					LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3), TODAY);
		assertThat(chart.bars()).extracting(Bar::label)
			.containsExactly("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Today");
		Bar today = chart.bars().get(6);
		assertThat(today.today()).isTrue();
		assertThat(today.date()).isEqualTo("4 Oct");
		assertThat(today.longLabel()).isEqualTo("Sunday 4 Oct");
		assertThat(chart.bars().subList(0, 6)).noneMatch(Bar::today);
	}

	@Test
	void anEmptyWeekHasNoBarsAndADefaultScale() {
		RevenueChart chart = RevenueChart.of(TODAY, Map.of(TODAY.minusDays(7), new BigDecimal("900"))); // too old
		assertThat(chart.isEmpty()).isTrue();
		assertThat(chart.total()).isEqualByComparingTo("0");
		assertThat(chart.bars()).allMatch(b -> b.height() == 0 && b.y() == RevenueChart.HEIGHT);
		assertThat(chart.ticks()).extracting(Tick::amount).map(BigDecimal::toPlainString)
			.containsExactly("100", "50.00", "0");
	}

	@Test
	void barsScaleToARoundTopAndSitOnTheBaseline() {
		RevenueChart chart = RevenueChart.of(TODAY, Map.of(TODAY, new BigDecimal("560.00"),
				TODAY.minusDays(1), new BigDecimal("1240.00"), TODAY.minusDays(6), new BigDecimal("60.00")));
		assertThat(chart.isEmpty()).isFalse();
		assertThat(chart.total()).isEqualByComparingTo("1860.00");
		// best day 1,240 -> top of the scale 2,000, half 1,000
		assertThat(chart.ticks()).extracting(Tick::amount).map(BigDecimal::toPlainString)
			.containsExactly("2000", "1000.00", "0");
		assertThat(chart.ticks()).extracting(Tick::percent).containsExactly(100, 50, 0);
		Bar today = chart.bars().get(6);
		Bar yesterday = chart.bars().get(5);
		Bar first = chart.bars().get(0);
		assertThat(today.height()).isEqualTo(56.0); // 560 / 2000 x 200
		assertThat(yesterday.height()).isEqualTo(124.0);
		assertThat(first.height()).isEqualTo(6.0);
		assertThat(chart.bars().get(3).height()).isZero();
		for (Bar bar : chart.bars()) {
			assertThat(bar.y() + bar.height()).isEqualTo(RevenueChart.HEIGHT);
			assertThat(bar.x()).isGreaterThanOrEqualTo(0);
			assertThat(bar.x() + bar.width()).isLessThanOrEqualTo(RevenueChart.WIDTH);
		}
		// evenly spaced, centred in seven equal slots
		assertThat(chart.bars()).extracting(Bar::x).containsExactly(22.0, 122.0, 222.0, 322.0, 422.0, 522.0, 622.0);
		assertThat(today.width()).isEqualTo(56.0);
	}

	@Test
	void aTinyDayIsStillVisible() {
		RevenueChart chart = RevenueChart.of(TODAY, Map.of(TODAY, new BigDecimal("50000"),
				TODAY.minusDays(2), new BigDecimal("1")));
		assertThat(chart.bars().get(4).height()).isEqualTo(RevenueChart.MIN_BAR);
		assertThat(chart.bars().get(6).height()).isEqualTo(RevenueChart.HEIGHT); // exactly the top
	}

	@Test
	void niceCeilingIsOneTwoOrFiveTimesAPowerOfTen() {
		assertThat(RevenueChart.niceCeiling(new BigDecimal("0.00"))).isEqualByComparingTo("100");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("45.50"))).isEqualByComparingTo("100");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("100.00"))).isEqualByComparingTo("100");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("100.01"))).isEqualByComparingTo("200");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("250.00"))).isEqualByComparingTo("500");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("999.99"))).isEqualByComparingTo("1000");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("1000.00"))).isEqualByComparingTo("1000");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("5000"))).isEqualByComparingTo("5000");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("5000.50"))).isEqualByComparingTo("10000");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("123456.50"))).isEqualByComparingTo("200000");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("1E+3"))).isEqualByComparingTo("1000");
	}

	@Test
	void anyNumberOfDaysWithItsOwnScaleFloor() {
		// T38: the admin dashboard's orders per day, 14 days, counts with a floor of 10
		RevenueChart chart = RevenueChart.of(TODAY, 14, BigDecimal.TEN,
				Map.of(TODAY.minusDays(13), new BigDecimal("3"), TODAY, new BigDecimal("7")));
		assertThat(chart.bars()).hasSize(14);
		assertThat(chart.bars().get(0).day()).isEqualTo(LocalDate.of(2026, 9, 21));
		assertThat(chart.bars().get(13).today()).isTrue();
		assertThat(chart.ticks()).extracting(Tick::amount)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(BigDecimal.TEN, new BigDecimal("5"), BigDecimal.ZERO);
		assertThat(chart.bars().get(13).height()).isEqualTo(140.0); // 7 of 10 x 200
		assertThat(chart.total()).isEqualByComparingTo("10");
		double slot = (double) RevenueChart.WIDTH / 14;
		for (int i = 0; i < 14; i++) {
			Bar bar = chart.bars().get(i);
			assertThat(bar.width()).isEqualTo(Math.round(slot * 0.56));
			assertThat(bar.x()).isGreaterThanOrEqualTo(i * slot).isLessThan((i + 1) * slot);
			assertThat(bar.x() + bar.width()).isLessThanOrEqualTo((i + 1) * slot + 0.1);
		}
		assertThat(RevenueChart.niceCeiling(new BigDecimal("11"), BigDecimal.TEN)).isEqualByComparingTo("20");
		assertThat(RevenueChart.niceCeiling(new BigDecimal("0"), BigDecimal.TEN)).isEqualByComparingTo("10");
	}

	@Test
	void averageOrderValueRoundsHalfUpAndIsZeroWithoutOrders() {
		assertThat(new Today(3, new BigDecimal("100.00")).averageOrderValue()).isEqualByComparingTo("33.33");
		assertThat(new Today(3, new BigDecimal("100.01")).averageOrderValue()).isEqualByComparingTo("33.34");
		assertThat(new Today(2, new BigDecimal("560.00")).averageOrderValue()).isEqualByComparingTo("280.00");
		assertThat(new Today(0, new BigDecimal("0.00")).averageOrderValue()).isEqualByComparingTo("0.00");
		assertThat(new Today(2, new BigDecimal("0.05")).averageOrderValue().toPlainString()).isEqualTo("0.03");
	}

}
