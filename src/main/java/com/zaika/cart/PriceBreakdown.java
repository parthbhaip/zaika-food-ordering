package com.zaika.cart;

import java.math.BigDecimal;

/**
 * The bill for one order, every amount in INR at scale 2. Built by {@link PricingService}; checkout snapshots it
 * onto the order.
 *
 * @param itemTotal   sum of the dishes at their current prices
 * @param discount    coupon discount, never more than the item total
 * @param deliveryFee the restaurant's fee, or zero once the item total is above the free-delivery threshold
 * @param platformFee flat fee per order
 * @param gst         GST on (item total - discount)
 * @param grandTotal  what the customer pays: item total - discount + delivery fee + platform fee + GST
 */
public record PriceBreakdown(BigDecimal itemTotal, BigDecimal discount, BigDecimal deliveryFee,
		BigDecimal platformFee, BigDecimal gst, BigDecimal grandTotal) {

	/** The amount GST is charged on. */
	public BigDecimal taxableAmount() {
		return itemTotal.subtract(discount);
	}

}
