package com.zaika.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * How an online order was paid, for its order page. The payment package implements it (PaymentService), so the
 * dependency runs one way only: payment uses order, never the other way round.
 */
public interface PaymentReceipts {

	/** The payment that paid the order, if one succeeded. */
	Optional<Receipt> paidWith(long orderId);

	/** Every online payment attempt for the order, oldest first (the admin's order timeline). */
	List<Attempt> attempts(long orderId);

	/** What the order page shows of a payment: the masked instrument ("Card ending 1111") and the gateway reference. */
	record Receipt(String instrument, String gatewayReference) {
	}

	/**
	 * One payment attempt as the admin sees it: when, how ("UPI", "Card"), the masked instrument, the amount, and the
	 * gateway's reference (success) or reason (failure).
	 */
	record Attempt(LocalDateTime at, boolean succeeded, String channel, String instrument, BigDecimal amount,
			String gatewayReference, String failureReason) {
	}

}
