package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * T36: the customer's 4-digit delivery code. A wrong code counts, the fifth wrong one in a row locks the code for ten
 * minutes (no tries meanwhile, a fresh count after), and the right code clears the count.
 */
class DeliveryOtpTest {

	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 19, 30);

	final Order order = OrderWorkflowTest.orderAt(OrderStatus.OUT_FOR_DELIVERY);

	String right() {
		return order.getDeliveryOtp();
	}

	String wrong() {
		return right().equals("0000") ? "1111" : "0000";
	}

	@Test
	void theCodeIsFourDigits() {
		assertThat(right()).matches("[0-9]{4}");
	}

	@Test
	void theRightCodeMatchesAndAWrongOneCounts() {
		assertThat(order.checkDeliveryOtp(wrong(), NOW)).isFalse();
		assertThat(order.otpAttemptsLeft()).isEqualTo(4);
		assertThat(order.checkDeliveryOtp(null, NOW)).isFalse();
		assertThat(order.otpAttemptsLeft()).isEqualTo(3);
		assertThat(order.checkDeliveryOtp(right(), NOW)).isTrue();
		assertThat(order.otpAttemptsLeft()).isEqualTo(Order.MAX_OTP_ATTEMPTS);
		assertThat(order.otpLockedAt(NOW)).isFalse();
	}

	@Test
	void theFifthWrongCodeInARowLocksItForTenMinutes() {
		for (int i = 1; i < Order.MAX_OTP_ATTEMPTS; i++) {
			assertThat(order.checkDeliveryOtp(wrong(), NOW)).isFalse();
			assertThat(order.otpLockedAt(NOW)).isFalse();
		}
		assertThat(order.otpAttemptsLeft()).isEqualTo(1);
		assertThat(order.checkDeliveryOtp(wrong(), NOW)).isFalse();

		assertThat(order.getOtpLockedUntil()).isEqualTo(NOW.plusMinutes(10));
		assertThat(order.otpLockedAt(NOW)).isTrue();
		assertThat(order.otpLockedAt(NOW.plusMinutes(9).plusSeconds(59))).isTrue();
		assertThatThrownBy(() -> order.checkDeliveryOtp(right(), NOW.plusMinutes(9)))
			.isInstanceOf(IllegalStateException.class);

		LocalDateTime later = NOW.plusMinutes(10);
		assertThat(order.otpLockedAt(later)).isFalse();
		assertThat(order.otpAttemptsLeft()).isEqualTo(Order.MAX_OTP_ATTEMPTS); // a fresh count after the lock
		assertThat(order.checkDeliveryOtp(right(), later)).isTrue();
		assertThat(order.getOtpLockedUntil()).isNull();
	}

	@Test
	void theRightCodeResetsTheCount() {
		for (int i = 1; i < Order.MAX_OTP_ATTEMPTS; i++) {
			order.checkDeliveryOtp(wrong(), NOW);
		}
		assertThat(order.checkDeliveryOtp(right(), NOW)).isTrue();
		assertThat(order.checkDeliveryOtp(wrong(), NOW)).isFalse();
		assertThat(order.otpLockedAt(NOW)).isFalse();
		assertThat(order.otpAttemptsLeft()).isEqualTo(4);
	}

}
