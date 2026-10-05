package com.zaika.catalog;

/** Moderation state. Only APPROVED restaurants are visible to customers. */
public enum RestaurantStatus {
	PENDING, APPROVED, REJECTED, SUSPENDED;

	/** Rejecting or suspending a restaurant must say why (the owner sees the reason). */
	public boolean needsReason() {
		return this == REJECTED || this == SUSPENDED;
	}
}
