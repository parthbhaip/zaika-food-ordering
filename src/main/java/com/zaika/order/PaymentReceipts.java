package com.zaika.order;

import java.util.Optional;

/**
 * How an online order was paid, for its order page. The payment package implements it (PaymentService), so the
 * dependency runs one way only: payment uses order, never the other way round.
 */
public interface PaymentReceipts {

	/** The payment that paid the order, if one succeeded. */
	Optional<Receipt> paidWith(long orderId);

	/** What the order page shows of a payment: the masked instrument ("Card ending 1111") and the gateway reference. */
	record Receipt(String instrument, String gatewayReference) {
	}

}
