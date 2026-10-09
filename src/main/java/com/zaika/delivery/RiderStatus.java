package com.zaika.delivery;

/** Review state of a delivery partner. Only APPROVED riders can go online and take deliveries. */
public enum RiderStatus {
	PENDING, APPROVED, SUSPENDED;

	/** A suspension must say why (the rider sees the reason). */
	public boolean needsReason() {
		return this == SUSPENDED;
	}
}
