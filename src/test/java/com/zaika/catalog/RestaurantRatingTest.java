package com.zaika.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.zaika.user.Role;
import com.zaika.user.User;

/** T43: the restaurant rating moves incrementally from the stored average and count, never from review rows. */
class RestaurantRatingTest {

	static Restaurant rated(String average, int count) {
		Restaurant r = new Restaurant(new User("Owner", "o@zaika.test", "9876543210", "x", Role.RESTAURANT_OWNER),
				"rassa", "Rassa");
		r.setRating(new BigDecimal(average), count);
		return r;
	}

	@Test
	void theFirstRatingIsTheAverage() {
		Restaurant r = rated("0", 0);
		r.addRating(4);
		assertThat(r.getRatingAverage()).isEqualByComparingTo("4.00");
		assertThat(r.getRatingCount()).isOne();
	}

	@Test
	void aNewRatingMovesASeededAverageByItsWeight() {
		Restaurant r = rated("4.50", 120); // seeded: 120 ratings, no review rows
		r.addRating(1);
		// (4.50 x 120 + 1) / 121 = 4.4710 -> 4.47
		assertThat(r.getRatingAverage()).isEqualByComparingTo("4.47");
		assertThat(r.getRatingCount()).isEqualTo(121);
		r.addRating(5);
		// (4.47 x 121 + 5) / 122 = 4.4743 -> 4.47
		assertThat(r.getRatingAverage()).isEqualByComparingTo("4.47");
		assertThat(r.getRatingCount()).isEqualTo(122);
		assertThat(r.getRatingAverage().scale()).isEqualTo(2);
	}

	@Test
	void removingARatingUndoesIt() {
		Restaurant r = rated("4.00", 3);
		r.addRating(1); // 13 / 4 = 3.25
		assertThat(r.getRatingAverage()).isEqualByComparingTo("3.25");
		r.removeRating(1);
		assertThat(r.getRatingAverage()).isEqualByComparingTo("4.00");
		assertThat(r.getRatingCount()).isEqualTo(3);
	}

	@Test
	void removingTheOnlyRatingLeavesNone() {
		Restaurant r = rated("0", 0);
		r.addRating(5);
		r.removeRating(5);
		assertThat(r.getRatingAverage()).isEqualByComparingTo("0.00");
		assertThat(r.getRatingCount()).isZero();
	}

	@Test
	void roundingDriftNeverLeavesOneToFive() {
		Restaurant r = rated("1.00", 2);
		r.removeRating(5); // (2 - 5) / 1 would be -3: kept at 1
		assertThat(r.getRatingAverage()).isEqualByComparingTo("1.00");
		Restaurant high = rated("5.00", 2);
		high.removeRating(1); // (10 - 1) / 1 = 9: kept at 5
		assertThat(high.getRatingAverage()).isEqualByComparingTo("5.00");
	}

}
