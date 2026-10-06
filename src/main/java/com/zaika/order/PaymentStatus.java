package com.zaika.order;

/** Where the money stands. A cancelled or rejected order that was paid moves to REFUND_PENDING. */
public enum PaymentStatus {

	PENDING("Pending"), PAID("Paid"), FAILED("Failed"), REFUND_PENDING("Refund in progress"), REFUNDED("Refunded");

	private final String label;

	PaymentStatus(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

}
