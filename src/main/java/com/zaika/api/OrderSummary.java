package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;

/**
 * An order in the customer's history (newest checkout first).
 *
 * @param paymentUrl the payment page while an online order still waits for payment within its time, else null
 */
public record OrderSummary(String code, OrderStatus status, String statusLabel, OrderResponse.RestaurantRef restaurant,
		List<OrderResponse.Item> items, int itemCount, BigDecimal grandTotal, PaymentMethod paymentMethod,
		PaymentStatus paymentStatus, OffsetDateTime createdAt, OffsetDateTime deliveredAt, String paymentUrl) {

	static OrderSummary of(Order o, LocalDateTime now, String baseUrl, ZoneId zone) {
		return new OrderSummary(o.getCode(), o.getStatus(), o.getStatus().getLabel(), OrderResponse.RestaurantRef.of(o),
				OrderResponse.Item.all(o), o.getItemCount(), o.getBill().grandTotal(), o.getPaymentMethod(),
				o.getPaymentStatus(), at(o.getCreatedAt(), zone), at(o.timestampOf(OrderStatus.DELIVERED), zone),
				OrderResponse.paymentUrl(o, !now.isBefore(o.payBy()), baseUrl));
	}

}
