package com.zaika.coupon;

/** Why a coupon cannot be applied. {@link CouponService} turns each into a message for the customer. */
public enum CouponProblem {
	/** Nothing to apply it to. */
	EMPTY_CART,
	/** No code typed. */
	BLANK,
	/** No coupon with that code. */
	UNKNOWN,
	/** Switched off by Zaika. */
	INACTIVE,
	NOT_STARTED,
	EXPIRED,
	/** The coupon belongs to another restaurant than the cart's. */
	OTHER_RESTAURANT,
	/** First-order coupon, and the customer has ordered before. */
	FIRST_ORDER_ONLY,
	/** The total limit is reached. */
	FULLY_CLAIMED,
	/** This customer reached the per-customer limit. */
	USED_UP,
	/** The item total is below the coupon's minimum order. */
	MIN_ORDER
}
