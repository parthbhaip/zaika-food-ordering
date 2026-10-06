package com.zaika.payment;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Takes money for an order. Zaika ships only {@link MockPaymentGateway} (no real money moves); docs/PAYMENTS.md
 * explains how a real gateway's test mode would take its place behind this interface.
 */
public interface PaymentGateway {

	/** Short name stored with every payment, e.g. "MOCK". At most 20 characters. */
	String name();

	/** Charges {@code charge.amount()} for the order and says how it went. Never throws for a declined payment. */
	Result charge(Charge charge);

	/**
	 * One payment attempt.
	 *
	 * @param instrument      a masked description of what pays ("Card ending 1111"), never a full card number or CVV
	 * @param simulateFailure test mode only: decline this attempt (the payment page's "Simulate failure")
	 */
	record Charge(String orderCode, BigDecimal amount, PaymentChannel channel, String instrument,
			boolean simulateFailure) {

		public Charge {
			Objects.requireNonNull(orderCode, "orderCode");
			Objects.requireNonNull(amount, "amount");
			Objects.requireNonNull(channel, "channel");
			Objects.requireNonNull(instrument, "instrument");
		}

	}

	/** The gateway's answer: a reference for a payment that went through, or the reason it was declined. */
	record Result(boolean succeeded, String reference, String failureReason) {

		public static Result success(String reference) {
			return new Result(true, Objects.requireNonNull(reference, "reference"), null);
		}

		public static Result declined(String failureReason) {
			return new Result(false, null, Objects.requireNonNull(failureReason, "failureReason"));
		}

	}

}
