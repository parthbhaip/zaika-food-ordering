package com.zaika.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.zaika.user.Role;
import com.zaika.user.User;

/** Pure rules on the catalog entities: opening hours (incl. past midnight), status reasons, money scale, slugs. */
class RestaurantTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	static User owner() {
		return new User("Ravi Jadhav", "ravi@example.com", "9876543210", "hash", Role.RESTAURANT_OWNER);
	}

	static Restaurant restaurant(String opens, String closes) {
		Restaurant r = new Restaurant(owner(), "test-kitchen", "Test Kitchen");
		r.setHours(LocalTime.parse(opens), LocalTime.parse(closes));
		return r;
	}

	static Clock istClockAt(String dateTime) {
		return Clock.fixed(LocalDateTime.parse(dateTime).atZone(IST).toInstant(), IST);
	}

	@Test
	void sameDayHoursAreOpenFromOpeningUntilJustBeforeClosing() {
		Restaurant r = restaurant("11:00", "23:00");

		assertThat(r.isOpenAt(LocalTime.parse("10:59"))).isFalse();
		assertThat(r.isOpenAt(LocalTime.parse("11:00"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("15:30"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("22:59:59"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("23:00"))).isFalse();
		assertThat(r.isOpenAt(LocalTime.parse("00:30"))).isFalse();
	}

	@Test
	void overnightHoursStayOpenPastMidnight() {
		Restaurant r = restaurant("18:00", "02:00");

		assertThat(r.isOpenAt(LocalTime.parse("17:59"))).isFalse();
		assertThat(r.isOpenAt(LocalTime.parse("18:00"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("23:59"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("00:00"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("01:59"))).isTrue();
		assertThat(r.isOpenAt(LocalTime.parse("02:00"))).isFalse();
		assertThat(r.isOpenAt(LocalTime.parse("12:00"))).isFalse();
	}

	@Test
	void closingAtMidnightAndRoundTheClockHours() {
		Restaurant midnight = restaurant("11:00", "00:00");
		assertThat(midnight.isOpenAt(LocalTime.parse("23:59"))).isTrue();
		assertThat(midnight.isOpenAt(LocalTime.parse("00:00"))).isFalse();
		assertThat(midnight.isOpenAt(LocalTime.parse("10:00"))).isFalse();

		Restaurant allDay = restaurant("00:00", "00:00");
		assertThat(allDay.isOpenAt(LocalTime.parse("00:00"))).isTrue();
		assertThat(allDay.isOpenAt(LocalTime.parse("04:00"))).isTrue();
	}

	@Test
	void isOpenNowReadsIndianTimeFromTheClock() {
		Restaurant lateNight = restaurant("18:00", "02:00");

		assertThat(lateNight.isOpenNow(istClockAt("2026-10-05T01:30"))).isTrue();
		assertThat(lateNight.isOpenNow(istClockAt("2026-10-05T09:00"))).isFalse();
		assertThat(lateNight.isOpenNow(istClockAt("2026-10-04T19:30"))).isTrue();
	}

	@Test
	void newRestaurantsArePendingAcceptingAndUnrated() {
		Restaurant r = new Restaurant(owner(), "misal-house", "Misal House");

		assertThat(r.getStatus()).isEqualTo(RestaurantStatus.PENDING);
		assertThat(r.getStatusReason()).isNull();
		assertThat(r.isAcceptingOrders()).isTrue();
		assertThat(r.getRatingAverage()).isEqualByComparingTo("0").hasScaleOf(2);
		assertThat(r.getRatingCount()).isZero();
		assertThat(r.getMinimumOrder()).isEqualTo(new BigDecimal("0.00"));
		assertThat(r.getDeliveryFee()).isEqualTo(new BigDecimal("0.00"));
	}

	@Test
	void onlyARestaurantOwnerAccountCanOwnARestaurant() {
		User customer = new User("Asha", "asha@example.com", "9876543210", "hash", Role.CUSTOMER);

		assertThatThrownBy(() -> new Restaurant(customer, "x", "X")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Restaurant(null, "x", "X")).isInstanceOf(NullPointerException.class);
	}

	@Test
	void rejectingOrSuspendingNeedsAReasonAndApprovingClearsIt() {
		Restaurant r = new Restaurant(owner(), "misal-house", "Misal House");

		assertThatThrownBy(() -> r.changeStatus(RestaurantStatus.REJECTED, " ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> r.changeStatus(RestaurantStatus.SUSPENDED, null)).isInstanceOf(IllegalArgumentException.class);
		assertThat(r.getStatus()).isEqualTo(RestaurantStatus.PENDING);

		r.changeStatus(RestaurantStatus.SUSPENDED, "  FSSAI licence expired ");
		assertThat(r.getStatus()).isEqualTo(RestaurantStatus.SUSPENDED);
		assertThat(r.getStatusReason()).isEqualTo("FSSAI licence expired");

		r.changeStatus(RestaurantStatus.APPROVED, "ignored");
		assertThat(r.getStatus()).isEqualTo(RestaurantStatus.APPROVED);
		assertThat(r.getStatusReason()).isNull();
	}

	@Test
	void moneyAndRatingAreStoredAtScaleTwoRoundedHalfUp() {
		Restaurant r = new Restaurant(owner(), "misal-house", "Misal House");
		r.setCostForTwo(new BigDecimal("300"));
		r.setDeliveryFee(new BigDecimal("29.995"));
		r.setRating(new BigDecimal("4.235"), 120);

		assertThat(r.getCostForTwo()).isEqualTo(new BigDecimal("300.00"));
		assertThat(r.getDeliveryFee()).isEqualTo(new BigDecimal("30.00"));
		assertThat(r.getRatingAverage()).isEqualTo(new BigDecimal("4.24"));
		assertThat(r.getRatingCount()).isEqualTo(120);

		MenuItem item = new MenuItem(new MenuCategory(r, "Misal", 1), "Kolhapuri Misal", new BigDecimal("89.5"), true);
		assertThat(item.getPrice()).isEqualTo(new BigDecimal("89.50"));
		assertThat(item.getRestaurant()).isSameAs(r);
		assertThat(item.isOrderable()).isTrue();
		item.setAvailable(false);
		assertThat(item.isOrderable()).isFalse();
		item.setAvailable(true);
		item.setArchived(true);
		assertThat(item.isOrderable()).isFalse();
	}

	@Test
	void slugsAreLowerCaseAsciiWordsJoinedByDashes() {
		assertThat(Slugs.slugify("South Indian")).isEqualTo("south-indian");
		assertThat(Slugs.slugify("  Biryani & Kebabs! ")).isEqualTo("biryani-kebabs");
		assertThat(Slugs.slugify("Café Crème")).isEqualTo("cafe-creme");
		assertThat(Slugs.slugify("Tambda--Pandhra Rassa")).isEqualTo("tambda-pandhra-rassa");
		assertThat(Slugs.slugify("&&")).isEmpty();
		assertThat(new Cuisine(" North Indian ").getSlug()).isEqualTo("north-indian");
		assertThat(new Cuisine(" North Indian ").getName()).isEqualTo("North Indian");
		assertThat("south-indian").matches(Slugs.PATTERN);
		assertThat("South-Indian").doesNotMatch(Slugs.PATTERN);
	}

}
