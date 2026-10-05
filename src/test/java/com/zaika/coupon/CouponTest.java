package com.zaika.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.zaika.catalog.Restaurant;
import com.zaika.user.Role;
import com.zaika.user.User;

/** The coupon rules on their own: eligibility, the order reasons are reported in, and the discount amount. */
class CouponTest {

	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 19, 30);

	static final long RESTAURANT = 7L;

	static final Coupon.Usage UNUSED = new Coupon.Usage(0, 0, false);

	static Coupon percent(String value) {
		return new Coupon("TEST", "Test coupon", CouponType.PERCENT, new BigDecimal(value), NOW.minusDays(1));
	}

	static Coupon flat(String value) {
		return new Coupon("TEST", "Test coupon", CouponType.FLAT, new BigDecimal(value), NOW.minusDays(1));
	}

	static Restaurant restaurant(long id) {
		User owner = new User("Owner", "owner" + id + "@zaika.test", "9876543210", "hash", Role.RESTAURANT_OWNER);
		Restaurant restaurant = new Restaurant(owner, "kitchen-" + id, "Kitchen " + id);
		ReflectionTestUtils.setField(restaurant, "id", id);
		return restaurant;
	}

	static CouponProblem check(Coupon coupon, String itemTotal, Coupon.Usage usage) {
		return coupon.problem(NOW, RESTAURANT, new BigDecimal(itemTotal), usage);
	}

	@Test
	void aPlainActiveCouponWorks() {
		assertThat(check(flat("50"), "100", UNUSED)).isNull();
	}

	@Test
	void anInactiveCouponIsRejected() {
		Coupon coupon = flat("50");
		coupon.setActive(false);
		assertThat(check(coupon, "100", UNUSED)).isEqualTo(CouponProblem.INACTIVE);
	}

	@Test
	void theValidityWindowStartsInclusiveAndEndsExclusive() {
		Coupon coupon = flat("50");
		coupon.setValidity(NOW, NOW.plusHours(1));
		assertThat(check(coupon, "100", UNUSED)).as("from the first moment").isNull();
		assertThat(coupon.problem(NOW.minusNanos(1000), RESTAURANT, BigDecimal.TEN, UNUSED))
			.isEqualTo(CouponProblem.NOT_STARTED);
		assertThat(coupon.problem(NOW.plusHours(1).minusNanos(1000), RESTAURANT, BigDecimal.TEN, UNUSED)).isNull();
		assertThat(coupon.problem(NOW.plusHours(1), RESTAURANT, BigDecimal.TEN, UNUSED))
			.isEqualTo(CouponProblem.EXPIRED);
	}

	@Test
	void withoutAnEndDateACouponNeverExpires() {
		assertThat(flat("50").problem(NOW.plusYears(30), RESTAURANT, BigDecimal.TEN, UNUSED)).isNull();
	}

	@Test
	void aRestaurantCouponWorksOnlyThere() {
		Coupon coupon = flat("50");
		coupon.setRestaurant(restaurant(RESTAURANT));
		assertThat(check(coupon, "100", UNUSED)).isNull();
		coupon.setRestaurant(restaurant(8L));
		assertThat(check(coupon, "100", UNUSED)).isEqualTo(CouponProblem.OTHER_RESTAURANT);
	}

	@Test
	void aFirstOrderCouponIsOnlyForCustomersWhoNeverOrdered() {
		Coupon coupon = flat("50");
		coupon.setFirstOrderOnly(true);
		assertThat(check(coupon, "100", UNUSED)).isNull();
		assertThat(check(coupon, "100", new Coupon.Usage(0, 0, true))).isEqualTo(CouponProblem.FIRST_ORDER_ONLY);

		Coupon anyOrder = flat("50");
		assertThat(check(anyOrder, "100", new Coupon.Usage(0, 0, true))).as("not first-order only").isNull();
	}

	@Test
	void theTotalLimitCountsEveryonesUses() {
		Coupon coupon = flat("50");
		coupon.setTotalLimit(100);
		assertThat(check(coupon, "100", new Coupon.Usage(0, 99, false))).isNull();
		assertThat(check(coupon, "100", new Coupon.Usage(0, 100, false))).isEqualTo(CouponProblem.FULLY_CLAIMED);
	}

	@Test
	void thePerCustomerLimitCountsTheirOwnUses() {
		Coupon coupon = flat("50");
		coupon.setPerUserLimit(2);
		assertThat(check(coupon, "100", new Coupon.Usage(1, 500, false))).isNull();
		assertThat(check(coupon, "100", new Coupon.Usage(2, 500, false))).isEqualTo(CouponProblem.USED_UP);
	}

	@Test
	void theMinimumOrderIsInclusiveAndMeasuredBeforeTheDiscount() {
		Coupon coupon = flat("50");
		coupon.setMinOrder(new BigDecimal("199"));
		assertThat(check(coupon, "198.99", UNUSED)).isEqualTo(CouponProblem.MIN_ORDER);
		assertThat(check(coupon, "199.00", UNUSED)).isNull();
	}

	@Test
	void theReasonTheCustomerCanDoLeastAboutComesFirst() {
		Coupon coupon = flat("50");
		coupon.setMinOrder(new BigDecimal("500"));
		coupon.setPerUserLimit(1);
		coupon.setTotalLimit(1);
		coupon.setFirstOrderOnly(true);
		coupon.setRestaurant(restaurant(8L));
		Coupon.Usage spent = new Coupon.Usage(1, 1, true);

		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.OTHER_RESTAURANT);
		coupon.setRestaurant(null);
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.FIRST_ORDER_ONLY);
		coupon.setFirstOrderOnly(false);
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.FULLY_CLAIMED);
		coupon.setTotalLimit(null);
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.USED_UP);
		coupon.setPerUserLimit(null);
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.MIN_ORDER);

		coupon.setValidity(NOW.plusDays(1), null);
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.NOT_STARTED);
		coupon.setValidity(NOW.minusDays(2), NOW.minusDays(1));
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.EXPIRED);
		coupon.setActive(false);
		assertThat(check(coupon, "100", spent)).isEqualTo(CouponProblem.INACTIVE);
	}

	@Test
	void aPercentageIsRoundedHalfUpToPaise() {
		assertThat(percent("50").discountOn(new BigDecimal("458"))).isEqualByComparingTo("229.00");
		assertThat(percent("10").discountOn(new BigDecimal("100.05"))).isEqualTo(new BigDecimal("10.01")); // 10.005
		assertThat(percent("10").discountOn(new BigDecimal("100.04"))).isEqualTo(new BigDecimal("10.00")); // 10.004
		assertThat(percent("12.5").discountOn(new BigDecimal("99"))).isEqualTo(new BigDecimal("12.38")); // 12.375
	}

	@Test
	void aPercentageStopsAtTheMaximumDiscount() {
		Coupon coupon = percent("50");
		coupon.setMaxDiscount(new BigDecimal("100"));
		assertThat(coupon.discountOn(new BigDecimal("150"))).isEqualTo(new BigDecimal("75.00"));
		assertThat(coupon.discountOn(new BigDecimal("200"))).isEqualTo(new BigDecimal("100.00"));
		assertThat(coupon.discountOn(new BigDecimal("1000"))).isEqualTo(new BigDecimal("100.00"));
	}

	@Test
	void aFlatDiscountNeverExceedsTheItemTotal() {
		assertThat(flat("75").discountOn(new BigDecimal("499"))).isEqualTo(new BigDecimal("75.00"));
		assertThat(flat("75").discountOn(new BigDecimal("60"))).isEqualTo(new BigDecimal("60.00"));
		assertThat(percent("100").discountOn(new BigDecimal("60"))).isEqualTo(new BigDecimal("60.00"));
		assertThat(flat("75").discountOn(BigDecimal.ZERO)).isEqualTo(new BigDecimal("0.00"));
	}

	@Test
	void codesAreStoredUpperCaseWithoutSpaces() {
		assertThat(Coupon.normalizeCode("  welcome 50 ")).isEqualTo("WELCOME50");
		assertThat(Coupon.normalizeCode(null)).isEmpty();
		assertThat(new Coupon("feast75", "x", CouponType.FLAT, BigDecimal.TEN, NOW).getCode()).isEqualTo("FEAST75");
	}

	@Test
	void valuesAndWindowsOutOfRangeAreRefused() {
		assertThatThrownBy(() -> percent("100.01")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> percent("0")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> flat("-5")).isInstanceOf(IllegalArgumentException.class);
		assertThat(flat("250").getValue()).isEqualTo(new BigDecimal("250.00"));
		assertThatThrownBy(() -> flat("5").setValidity(NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
	}

}
