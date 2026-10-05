package com.zaika.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.zaika.config.AppProperties;

/**
 * The one place that turns an item total into a bill. Cart, checkout and the order snapshot all price through here.
 * Rules: free delivery once the item total (before any discount) is above {@code app.free-delivery-threshold};
 * the discount is capped at the item total; GST is charged on (item total - discount) only; an empty order costs
 * nothing (no platform or delivery fee on zero dishes). Every amount is rounded HALF_UP to 2 decimals.
 */
@Service
public class PricingService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final AppProperties app;

	public PricingService(AppProperties app) {
		this.app = app;
	}

	/** The bill for a re-checked cart, with the restaurant's own delivery fee. */
	public PriceBreakdown price(CartView cart, BigDecimal discount) {
		if (cart.isEmpty()) {
			return price(ZERO, ZERO, discount);
		}
		return price(cart.itemTotal(), cart.restaurant().getDeliveryFee(), discount);
	}

	/**
	 * @param itemTotal             sum of the dishes
	 * @param restaurantDeliveryFee the restaurant's fee below the free-delivery threshold
	 * @param discount              coupon discount (zero without a coupon); anything above the item total is dropped
	 * @throws IllegalArgumentException if any amount is negative
	 */
	public PriceBreakdown price(BigDecimal itemTotal, BigDecimal restaurantDeliveryFee, BigDecimal discount) {
		BigDecimal items = money(itemTotal, "itemTotal");
		BigDecimal fee = money(restaurantDeliveryFee, "restaurantDeliveryFee");
		BigDecimal off = money(discount, "discount").min(items);
		if (items.signum() == 0) {
			return new PriceBreakdown(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
		}
		BigDecimal delivery = items.compareTo(app.freeDeliveryThreshold()) > 0 ? ZERO : fee;
		BigDecimal platform = money(app.platformFee(), "platformFee");
		BigDecimal gst = items.subtract(off).multiply(app.gstRate()).setScale(2, RoundingMode.HALF_UP);
		BigDecimal grand = items.subtract(off).add(delivery).add(platform).add(gst);
		return new PriceBreakdown(items, off, delivery, platform, gst, grand);
	}

	/**
	 * The smallest whole-rupee amount that, added to the cart, makes delivery free ("Add ₹42 more for free
	 * delivery"); {@code null} when delivery is already free, the restaurant charges no fee, or the cart is empty.
	 */
	public BigDecimal freeDeliveryGap(CartView cart) {
		if (cart.isEmpty() || cart.restaurant().getDeliveryFee().signum() == 0) {
			return null;
		}
		BigDecimal gap = app.freeDeliveryThreshold().subtract(cart.itemTotal());
		if (gap.signum() < 0) {
			return null;
		}
		// free only strictly above the threshold, so ₹458 needs ₹42 more (₹500), not ₹41 (₹499)
		return gap.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE).setScale(2);
	}

	/** The GST rate as a percentage for labels: "5". */
	public String gstPercent() {
		return app.gstRate().movePointRight(2).stripTrailingZeros().toPlainString();
	}

	private static BigDecimal money(BigDecimal amount, String name) {
		Objects.requireNonNull(amount, name);
		if (amount.signum() < 0) {
			throw new IllegalArgumentException(name + " must not be negative: " + amount);
		}
		return amount.setScale(2, RoundingMode.HALF_UP);
	}

}
