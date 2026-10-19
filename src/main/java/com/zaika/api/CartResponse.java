package com.zaika.api;

import static com.zaika.api.RestaurantSummary.absolute;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zaika.cart.CartView;
import com.zaika.cart.PriceBreakdown;
import com.zaika.catalog.MenuItem;
import com.zaika.coupon.CouponService.CartCoupon;
import com.zaika.storage.ImageUrls;

/**
 * The customer's cart, re-checked against the menu on every read (as the website's cart page is).
 *
 * @param restaurant where the dishes come from; null when the cart is empty
 * @param takingOrders the restaurant is open and accepting orders now
 * @param opensAt "HH:mm" a restaurant closed by its hours opens again; null otherwise
 * @param notices what changed since the last look (price changes, dishes taken out), each reported once
 * @param coupon the applied coupon, re-checked; null if none
 * @param couponNotice why an applied coupon was just taken off, or null
 * @param offers coupons open at this restaurant
 * @param freeDeliveryGap add this much food for free delivery; null when delivery is already free
 * @param minimumOrderShortfall food still needed for the restaurant's minimum order (0 once reached)
 * @param canCheckout not empty, taking orders and the minimum reached: {@code POST /api/v1/orders} can place it
 */
public record CartResponse(RestaurantRef restaurant, boolean takingOrders, @JsonFormat(pattern = "HH:mm") LocalTime opensAt,
		List<Line> lines, int itemCount, String instructions, List<String> notices, AppliedCoupon coupon,
		String couponNotice, List<Offer> offers, BillResponse bill, BigDecimal freeDeliveryGap,
		BigDecimal minimumOrderShortfall, boolean canCheckout) {

	static CartResponse of(CartView cart, CartCoupon coupon, PriceBreakdown bill, BigDecimal freeDeliveryGap,
			ImageUrls images, String baseUrl) {
		RestaurantRef restaurant = cart.isEmpty() ? null
				: new RestaurantRef(cart.restaurant().getSlug(), cart.restaurant().getName(),
						cart.restaurant().getMinimumOrder());
		return new CartResponse(restaurant, cart.takingOrders(), cart.opensAt(),
				cart.lines().stream().map(line -> Line.of(line, images, baseUrl)).toList(), cart.itemCount(),
				cart.instructions(), cart.notices(),
				coupon.applied() == null ? null : new AppliedCoupon(coupon.applied().code(), coupon.applied().headline(),
						coupon.applied().discount()),
				coupon.notice(),
				coupon.offers().stream().map(o -> new Offer(o.code(), o.headline(), o.description(), o.terms())).toList(),
				BillResponse.of(bill), freeDeliveryGap, cart.minimumOrderShortfall(), cart.canCheckout());
	}

	/** The cart's restaurant; {@code minimumOrder} is the least food value it takes an order for. */
	public record RestaurantRef(String slug, String name, BigDecimal minimumOrder) {
	}

	/** One dish at today's price; {@code itemId} is the id for {@code PUT /api/v1/cart/items/{itemId}}. */
	public record Line(long itemId, String name, boolean veg, BigDecimal price, int quantity, BigDecimal lineTotal,
			String imageUrl) {

		static Line of(CartView.Line line, ImageUrls images, String baseUrl) {
			MenuItem item = line.item();
			return new Line(item.getId(), item.getName(), item.isVeg(), item.getPrice(), line.quantity(),
					line.lineTotal().setScale(2), absolute(baseUrl, images.dish(item)));
		}

	}

	/** {@code discount} is what it takes off the item total right now. */
	public record AppliedCoupon(String code, String headline, BigDecimal discount) {
	}

	/** A coupon the customer can apply here; {@code terms} may be empty. */
	public record Offer(String code, String headline, String description, String terms) {
	}

}
