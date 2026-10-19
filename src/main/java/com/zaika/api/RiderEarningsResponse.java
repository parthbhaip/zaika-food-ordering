package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.zaika.delivery.RiderEarning;
import com.zaika.delivery.RiderEarningsService;

/**
 * The rider's earnings, as the website's {@code /partner/earnings} shows them: totals for today, this week (from
 * Monday) and this month, the cash in hand and one page of deliveries, newest first. Riders hand in the cash they
 * collected to the partner team at the end of each day, so {@code cashInHand} is today's.
 */
public record RiderEarningsResponse(LocalDate today, LocalDate weekStart, LocalDate monthStart, Totals todayTotals,
		Totals week, Totals month, Totals cashInHand, PageResponse<Earning> history) {

	static RiderEarningsResponse of(RiderEarningsService.Earnings e, ZoneId zone) {
		RiderEarningsService.Periods p = e.periods();
		return new RiderEarningsResponse(p.today(), p.weekStart(), p.monthStart(), Totals.of(e.summary().today()),
				Totals.of(e.summary().week()), Totals.of(e.summary().month()), Totals.of(e.cashInHand()),
				PageResponse.of(e.history(), earning -> Earning.of(earning, zone)));
	}

	/**
	 * One period.
	 *
	 * @param earned what the rider earned
	 * @param cashOrders deliveries paid in cash at the door
	 * @param cash the cash collected
	 */
	public record Totals(int deliveries, BigDecimal earned, int cashOrders, BigDecimal cash) {

		static Totals of(RiderEarningsService.Totals t) {
			return new Totals(t.deliveries(), t.earned(), t.cashOrders(), t.cash());
		}

	}

	/**
	 * One delivery: {@code amount} = {@code basePayout} + {@code feeShare} (the rider's share of the delivery fee);
	 * {@code cashCollected} is zero for an order paid online.
	 */
	public record Earning(String code, String restaurant, String area, OffsetDateTime deliveredAt, BigDecimal amount,
			BigDecimal basePayout, BigDecimal feeShare, BigDecimal cashCollected) {

		static Earning of(RiderEarning e, ZoneId zone) {
			return new Earning(e.getOrder().getCode(), e.getOrder().getRestaurant().getName(),
					e.getOrder().getRestaurant().getArea(), at(e.getEarnedAt(), zone), e.getAmount(), e.getBasePayout(),
					e.getFeeShare(), e.getCashCollected());
		}

	}

}
