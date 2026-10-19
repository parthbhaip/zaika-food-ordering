package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.zaika.catalog.Restaurant;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;

/**
 * One of the restaurant's orders as its kitchen sees it (the website's live board and kitchen ticket). No customer
 * phone or address: the rider handles the drop.
 *
 * @param customerName the customer's name, for the bag
 * @param itemTotal the food value (what the kitchen sells), before fees and discounts
 * @param restaurantNote the customer's note for the kitchen, or null
 * @param readyBy when the food should be packed (the customer's ETA minus the ride); null until accepted
 * @param late accepted or cooking and past {@code readyBy}
 * @param riderName the delivery partner once one has taken it, else null
 * @param cancelReason why it was rejected or cancelled, else null
 * @param version changes whenever the order changes
 */
public record OwnerOrderResponse(String code, OrderStatus status, String statusLabel, String customerName,
		List<OrderResponse.Item> items, int itemCount, BigDecimal itemTotal, PaymentMethod paymentMethod,
		PaymentStatus paymentStatus, String restaurantNote, OffsetDateTime placedAt, OffsetDateTime readyBy,
		boolean late, String riderName, String cancelReason, long version) {

	/** {@code o} with its customer, rider and dishes loaded. */
	static OwnerOrderResponse of(Order o, LocalDateTime now, ZoneId zone) {
		LocalDateTime readyBy = o.timestampOf(OrderStatus.ACCEPTED) == null ? null
				: o.getEstimatedDeliveryAt().minusMinutes(Restaurant.RIDE_MINUTES);
		boolean cooking = o.getStatus() == OrderStatus.ACCEPTED || o.getStatus() == OrderStatus.PREPARING;
		return new OwnerOrderResponse(o.getCode(), o.getStatus(), o.getStatus().getLabel(), o.getCustomer().getName(),
				OrderResponse.Item.all(o), o.getItemCount(), o.getBill().itemTotal(), o.getPaymentMethod(),
				o.getPaymentStatus(), o.getRestaurantNote(), at(o.timestampOf(OrderStatus.PLACED), zone),
				at(readyBy, zone), cooking && readyBy != null && now.isAfter(readyBy),
				o.getRider() == null ? null : o.getRider().getName(), o.getCancelReason(), o.getVersion());
	}

}
