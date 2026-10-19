package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.zaika.catalog.Restaurant;
import com.zaika.common.Formatters;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryService.Dashboard;
import com.zaika.delivery.DeliveryService.Offer;
import com.zaika.delivery.RiderStatus;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;

/**
 * The rider's home, as the website's {@code /partner} shows it: review status, the online switch, the current
 * delivery and the orders waiting for a rider. Poll it (or {@code /orders/available}) every 15 seconds while on duty;
 * {@code version} changes whenever the waiting list does.
 *
 * @param statusReason why the account is suspended, else null
 * @param onDuty approved and online: {@code availableOrders} is filled while there is no current delivery
 * @param currentDelivery the delivery the rider has taken and not finished, else null
 * @param availableOrders the oldest orders waiting for a rider (empty while offline or busy)
 */
public record RiderDashboardResponse(RiderStatus status, String statusReason, boolean online, boolean onDuty,
		Delivery currentDelivery, List<Available> availableOrders, String version) {

	static RiderDashboardResponse of(Dashboard d, Formatters fmt, ZoneId zone) {
		DeliveryPartnerProfile p = d.profile();
		return new RiderDashboardResponse(p.getStatus(), p.getStatusReason(), p.isOnline(), d.onDuty(),
				d.active() == null ? null : Delivery.of(d, fmt, zone),
				d.offers().stream().map(o -> Available.of(o, zone)).toList(), d.version());
	}

	/** Where to collect the food. */
	public record Pickup(String name, String address, String area, String phone) {

		static Pickup of(Restaurant r) {
			return new Pickup(r.getName(), r.getAddress() + ", " + r.getArea() + ", " + r.getCity() + " " + r.getPincode(),
					r.getArea(), r.getPhone());
		}

	}

	/** Where it goes: the customer's name, address and masked phone, and their note for the rider. */
	public record Drop(String name, String address, String street, String phone, String note) {
	}

	/**
	 * The rider's current delivery. Tap "picked up" at READY_FOR_PICKUP, then deliver with the customer's 4-digit
	 * code once OUT_FOR_DELIVERY.
	 *
	 * @param readyBy when the kitchen should have it packed
	 * @param cashToCollect the amount to collect at the door for an unpaid cash order, else null
	 * @param payout what delivering it earns the rider
	 */
	public record Delivery(String code, OrderStatus status, String statusLabel, Pickup pickup, Drop drop,
			List<OrderResponse.Item> items, int itemCount, PaymentMethod paymentMethod, PaymentStatus paymentStatus,
			BigDecimal cashToCollect, BigDecimal payout, OffsetDateTime readyBy, OffsetDateTime estimatedDeliveryAt,
			long version) {

		static Delivery of(Dashboard d, Formatters fmt, ZoneId zone) {
			Order o = d.active();
			return new Delivery(o.getCode(), o.getStatus(), o.getStatus().getLabel(), Pickup.of(o.getRestaurant()),
					new Drop(o.getCustomer().getName(), o.getAddress().oneLine(), o.getAddress().street(),
							fmt.maskedPhone(o.getCustomer().getPhone()), o.getDeliveryNote()),
					OrderResponse.Item.all(o), o.getItemCount(), o.getPaymentMethod(), o.getPaymentStatus(),
					d.cashToCollect(), d.activePayout(), at(d.readyBy(o), zone), at(o.getEstimatedDeliveryAt(), zone),
					o.getVersion());
		}

	}

	/**
	 * An order waiting for a rider: claim it with {@code POST /api/v1/rider/orders/{code}/claim}.
	 *
	 * @param dropStreet the street it goes to (the full address shows once claimed)
	 * @param readyNow packed and waiting at the restaurant
	 * @param readyBy when the kitchen should have it packed
	 * @param cashOrder the customer pays cash at the door
	 */
	public record Available(String code, Pickup pickup, String dropStreet, int itemCount, OrderStatus status,
			boolean readyNow, OffsetDateTime readyBy, boolean cashOrder, BigDecimal payout) {

		static Available of(Offer offer, ZoneId zone) {
			Order o = offer.order();
			return new Available(o.getCode(), Pickup.of(o.getRestaurant()), o.getAddress().street(), o.getItemCount(),
					o.getStatus(), o.getStatus() == OrderStatus.READY_FOR_PICKUP,
					at(o.getEstimatedDeliveryAt().minusMinutes(Restaurant.RIDE_MINUTES), zone),
					o.getPaymentMethod() == PaymentMethod.COD && o.getPaymentStatus() != PaymentStatus.PAID,
					offer.payout());
		}

	}

}
