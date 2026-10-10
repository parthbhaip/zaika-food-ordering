package com.zaika.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** T35: what a rider earns per delivery: the base payout plus their share of the order's delivery fee. */
class DeliveryPayoutTest {

	private static BigDecimal payout(String base, String share, String fee) {
		return DeliveryService.payout(new BigDecimal(base), new BigDecimal(share), new BigDecimal(fee));
	}

	@Test
	void baseIsToppedUpWithTheRidersShareOfTheDeliveryFee() {
		assertThat(payout("30.00", "0.80", "30.00")).isEqualTo(new BigDecimal("54.00"));
		assertThat(payout("30.00", "0.80", "45.00")).isEqualTo(new BigDecimal("66.00"));
		assertThat(payout("25.50", "1.00", "20.00")).isEqualTo(new BigDecimal("45.50"));
	}

	@Test
	void aFreeDeliveryOrderPaysTheBaseOnly() {
		assertThat(payout("30.00", "0.80", "0.00")).isEqualTo(new BigDecimal("30.00"));
		assertThat(payout("30.00", "0.00", "40.00")).isEqualTo(new BigDecimal("30.00"));
	}

	@Test
	void roundsHalfUpToThePaisa() {
		assertThat(payout("30.00", "0.75", "33.33")).isEqualTo(new BigDecimal("55.00")); // 24.9975
		assertThat(payout("30.00", "0.85", "0.03")).isEqualTo(new BigDecimal("30.03")); // 0.0255
		assertThat(payout("30.00", "0.15", "0.03")).isEqualTo(new BigDecimal("30.00")); // 0.0045
	}

}
