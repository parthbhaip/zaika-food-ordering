package com.zaika.delivery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.delivery.RiderEarningRepository.Earned;

/**
 * The rider's earnings page: what they earned today, this week (from Monday) and this month (all in Asia/Kolkata via
 * the clock), the cash in hand, and the history of delivered orders. Zaika has no cash deposit step yet, so "cash in
 * hand" is the cash collected today: riders settle it at the end of each day.
 */
@Service
public class RiderEarningsService {

	/** Deliveries per history page. */
	public static final int PAGE_SIZE = 20;

	private final DeliveryPartnerProfileRepository profiles;

	private final RiderEarningRepository earnings;

	private final Clock clock;

	RiderEarningsService(DeliveryPartnerProfileRepository profiles, RiderEarningRepository earnings, Clock clock) {
		this.profiles = profiles;
		this.earnings = earnings;
		this.clock = clock;
	}

	/**
	 * Page {@code page} (0-based) of the rider's earnings with the period totals. Empty for an account without a
	 * rider profile. Four statements at most (profile, totals, page, count).
	 */
	@Transactional(readOnly = true)
	public Optional<Earnings> earnings(long riderId, int page) {
		return profiles.findByUserId(riderId).map(profile -> {
			LocalDate today = LocalDate.now(clock);
			Periods periods = Periods.of(today);
			Summary summary = Summary.of(earnings.findEarnedSince(riderId, periods.earliest()), periods);
			Page<RiderEarning> history = earnings.findByRiderIdOrderByEarnedAtDescIdDesc(riderId,
					PageRequest.of(Math.max(page, 0), PAGE_SIZE));
			return new Earnings(profile, periods, summary, history);
		});
	}

	/** Where today, this week (Monday) and this month start. */
	public record Periods(LocalDate today, LocalDate weekStart, LocalDate monthStart) {

		static Periods of(LocalDate today) {
			return new Periods(today, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
					today.withDayOfMonth(1));
		}

		private static final DateTimeFormatter WEEK_START = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

		/** "Mon 28 Sep". */
		public String weekStartLabel() {
			return WEEK_START.format(weekStart);
		}

		/** "October". */
		public String monthName() {
			return monthStart.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
		}

		/** The earliest start: a week can begin in the month before. */
		LocalDateTime earliest() {
			return (weekStart.isBefore(monthStart) ? weekStart : monthStart).atStartOfDay();
		}

	}

	/** Deliveries, money earned and cash collected in one period. */
	public record Totals(int deliveries, BigDecimal earned, int cashOrders, BigDecimal cash) {

		static final Totals NONE = new Totals(0, BigDecimal.ZERO.setScale(2), 0, BigDecimal.ZERO.setScale(2));

		Totals plus(Earned e) {
			boolean cashOrder = e.cashCollected().signum() > 0;
			return new Totals(deliveries + 1, earned.add(e.amount()), cashOrders + (cashOrder ? 1 : 0),
					cash.add(e.cashCollected()));
		}

	}

	/** The three periods' totals. */
	public record Summary(Totals today, Totals week, Totals month) {

		static Summary of(List<Earned> earned, Periods periods) {
			Totals today = Totals.NONE;
			Totals week = Totals.NONE;
			Totals month = Totals.NONE;
			for (Earned e : earned) {
				LocalDate day = e.earnedAt().toLocalDate();
				if (!day.isBefore(periods.today())) {
					today = today.plus(e);
				}
				if (!day.isBefore(periods.weekStart())) {
					week = week.plus(e);
				}
				if (!day.isBefore(periods.monthStart())) {
					month = month.plus(e);
				}
			}
			return new Summary(today, week, month);
		}

	}

	/** The earnings page: profile (with account), periods, totals and one page of the delivery history. */
	public record Earnings(DeliveryPartnerProfile profile, Periods periods, Summary summary,
			Page<RiderEarning> history) {

		/** Cash collected today, which the rider still holds. */
		public Totals cashInHand() {
			return summary.today();
		}

	}

}
