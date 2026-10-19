package com.zaika.api;

import java.math.BigDecimal;

import com.zaika.cart.PriceBreakdown;

/**
 * A bill in INR (2 decimals): what the customer pays is {@code grandTotal} = item total - discount + delivery fee +
 * platform fee + GST (on the item total after the discount).
 */
public record BillResponse(BigDecimal itemTotal, BigDecimal discount, BigDecimal deliveryFee, BigDecimal platformFee,
		BigDecimal gst, BigDecimal grandTotal) {

	static BillResponse of(PriceBreakdown bill) {
		return new BillResponse(bill.itemTotal(), bill.discount(), bill.deliveryFee(), bill.platformFee(), bill.gst(),
				bill.grandTotal());
	}

}
