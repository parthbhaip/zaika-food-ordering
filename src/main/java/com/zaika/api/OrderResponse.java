package com.zaika.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.zaika.order.DeliveryAddress;
import com.zaika.order.Order;
import com.zaika.order.OrderHistoryService.Detail;
import com.zaika.order.OrderItem;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;
import com.zaika.review.Review;

/**
 * One of the customer's orders, as its page on the website shows it. Times are ISO-8601 with the +05:30 offset.
 *
 * @param paymentUrl the website's payment page while an online order still waits for payment (open it in a browser,
 *        signed in as the customer); null otherwise
 * @param payBy when an unpaid online order is cancelled automatically; null once paid or for cash orders
 * @param endedBy who ended a cancelled or rejected order, as a sentence; null otherwise
 * @param canCancel the restaurant has not accepted it yet: {@code POST /api/v1/orders/{code}/cancel} works
 * @param canReview delivered and not rated yet: {@code POST /api/v1/orders/{code}/review} works
 * @param review the customer's review of this order, or null
 * @param version changes whenever the order changes
 */
public record OrderResponse(String code, OrderStatus status, String statusLabel, RestaurantRef restaurant,
		List<Item> items, int itemCount, BillResponse bill, String couponCode, PaymentMethod paymentMethod,
		PaymentStatus paymentStatus, Payment payment, Address deliveryAddress, String restaurantNote,
		String deliveryNote, OffsetDateTime createdAt, OffsetDateTime placedAt, OffsetDateTime estimatedDeliveryAt,
		OffsetDateTime deliveredAt, OffsetDateTime payBy, String paymentUrl, String endedBy, String cancelReason,
		boolean canCancel, boolean canReview, ReviewResponse review, long version) {

	static OrderResponse of(Detail detail, Review review, String baseUrl, ZoneId zone) {
		Order o = detail.order();
		boolean delivered = o.getStatus() == OrderStatus.DELIVERED;
		return new OrderResponse(o.getCode(), o.getStatus(), o.getStatus().getLabel(), RestaurantRef.of(o),
				Item.all(o), o.getItemCount(), BillResponse.of(o.getBill()), o.getCouponCode(), o.getPaymentMethod(),
				o.getPaymentStatus(),
				detail.payment() == null ? null
						: new Payment(detail.payment().instrument(), detail.payment().gatewayReference()),
				Address.of(o.getAddress()), o.getRestaurantNote(), o.getDeliveryNote(), at(o.getCreatedAt(), zone),
				at(o.timestampOf(OrderStatus.PLACED), zone), at(o.getEstimatedDeliveryAt(), zone),
				at(o.timestampOf(OrderStatus.DELIVERED), zone),
				detail.awaitingPayment() ? at(detail.payBy(), zone) : null,
				paymentUrl(o, detail.paymentTimeUp(), baseUrl), detail.endedBy(), o.getCancelReason(),
				o.getStatus() == OrderStatus.PLACED, delivered && review == null,
				review == null ? null : ReviewResponse.of(review, zone), o.getVersion());
	}

	/** The payment page of an order still awaiting payment within its time; null otherwise. */
	static String paymentUrl(Order o, boolean timeUp, String baseUrl) {
		return o.getStatus() == OrderStatus.PENDING_PAYMENT && !timeUp ? baseUrl + "/pay/" + o.getCode() : null;
	}

	/** A Kolkata wall-clock time as an offset time ("2026-10-04T19:30:00+05:30"); null stays null. */
	static OffsetDateTime at(LocalDateTime time, ZoneId zone) {
		return time == null ? null : time.atZone(zone).toOffsetDateTime();
	}

	/** The restaurant; {@code phone} is its business number, to call about the order. */
	@Schema(name = "OrderRestaurant")
	public record RestaurantRef(String slug, String name, String phone) {

		static RestaurantRef of(Order o) {
			return new RestaurantRef(o.getRestaurant().getSlug(), o.getRestaurant().getName(),
					o.getRestaurant().getPhone());
		}

	}

	/** A dish as it was ordered (name and price at checkout). */
	public record Item(String name, boolean veg, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {

		static List<Item> all(Order o) {
			return o.getItems().stream().map(Item::of).toList();
		}

		static Item of(OrderItem i) {
			return new Item(i.getName(), i.isVeg(), i.getUnitPrice(), i.getQuantity(), i.getLineTotal());
		}

	}

	/** The online payment that paid the order (masked instrument only, never full card numbers). */
	public record Payment(String instrument, String reference) {
	}

	/** Where the order goes, as it was at checkout. */
	public record Address(String label, String house, String street, String landmark, String city, String pincode,
			String oneLine) {

		static Address of(DeliveryAddress a) {
			return new Address(a.label().name(), a.house(), a.street(), a.landmark(), a.city(), a.pincode(),
					a.oneLine());
		}

	}

}
