package com.zaika.notification;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import com.zaika.cart.PriceBreakdown;
import com.zaika.common.Formatters;
import com.zaika.config.AppProperties;
import com.zaika.notification.Notification.Kind;
import com.zaika.order.Order;
import com.zaika.order.OrderItem;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderStatusChanged;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;
import com.zaika.user.User;

/**
 * Tells the people on an order what just happened to it. Each {@link OrderStatusChanged} is handled just before its
 * transaction commits, so the notifications are saved with the change (and never for a change that is rolled back);
 * emails (order placed, delivered, and every party of an order Zaika support cancelled) are composed then and sent
 * after commit.
 *
 * <pre>
 * PLACED            customer (+ email), restaurant ("new order")
 * ACCEPTED          customer
 * PREPARING         customer
 * READY_FOR_PICKUP  customer, assigned rider
 * OUT_FOR_DELIVERY  customer
 * DELIVERED         customer (+ email)
 * REJECTED          customer
 * CANCELLED         by the customer: the restaurant, if it had the order
 *                   by the system (payment time ran out): the customer
 *                   by Zaika support: customer, restaurant if it had the order, assigned rider (all + email)
 * </pre>
 */
@Component
class OrderNotifications {

	private final NotificationService notifications;

	private final MailService mail;

	private final AppProperties app;

	private final Formatters fmt;

	OrderNotifications(NotificationService notifications, MailService mail, AppProperties app, Formatters fmt) {
		this.notifications = notifications;
		this.mail = mail;
		this.app = app;
		this.fmt = fmt;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void on(OrderStatusChanged event) {
		Order o = event.order();
		String code = o.getCode();
		String kitchen = o.getRestaurant().getName();
		String track = "/orders/" + code + "/track";
		switch (event.to()) {
			case PLACED -> {
				toCustomer(o, Kind.ORDER, "Order placed",
						kitchen + " has your order " + code + ". We'll tell you as soon as they accept it.", track);
				notifications.notify(o.getRestaurant().getOwner(), Kind.ORDER, "New order " + code,
						dishes(o) + " · " + fmt.money(o.getBill().grandTotal()) + " · "
								+ o.getPaymentMethod().getLabel() + ". Accept it to start cooking.",
						"/owner/orders/" + code);
				mailCustomer(o, "Order " + code + " placed with " + kitchen, placedMail(o));
			}
			case ACCEPTED -> toCustomer(o, Kind.ORDER, kitchen + " accepted your order",
					code + " is confirmed. Expected at your door by " + fmt.time(o.getEstimatedDeliveryAt()) + ".",
					track);
			case PREPARING -> toCustomer(o, Kind.ORDER, "Your food is being prepared",
					kitchen + " is cooking " + code + " now.", track);
			case READY_FOR_PICKUP -> {
				toCustomer(o, Kind.ORDER, "Packed and ready",
						code + " is packed at " + kitchen + " and waiting for its delivery partner.", track);
				if (o.getRider() != null) {
					notifications.notify(o.getRider(), Kind.DELIVERY, "Ready for pickup",
							code + " is packed at " + kitchen + ", " + o.getRestaurant().getArea()
									+ ". Head over to pick it up.",
							"/partner");
				}
			}
			case OUT_FOR_DELIVERY -> toCustomer(o, Kind.DELIVERY, "On the way",
					o.getRider().firstName() + " picked up " + code + " from " + kitchen
							+ ". Keep your delivery code handy: it's on the tracking page.",
					track);
			case DELIVERED -> {
				toCustomer(o, Kind.ORDER, "Delivered. Enjoy your meal!",
						code + " from " + kitchen + " was delivered. Tell us how it was.",
						"/orders/" + code + "#review");
				mailCustomer(o, "Delivered: your order from " + kitchen, deliveredMail(o));
			}
			case REJECTED -> toCustomer(o, Kind.ORDER, kitchen + " couldn't take your order",
					code + " was not accepted. Reason: " + Notification.sentence(o.getCancelReason()) + refund(o), "/orders/" + code);
			case CANCELLED -> cancelled(event);
			case PENDING_PAYMENT -> {
				// orders start here; nothing moves back to it
			}
		}
	}

	private void cancelled(OrderStatusChanged event) {
		Order o = event.order();
		String code = o.getCode();
		boolean kitchenHadIt = event.from() != OrderStatus.PENDING_PAYMENT;
		switch (event.actor().kind()) {
			case CUSTOMER -> {
				if (kitchenHadIt) {
					notifications.notify(o.getRestaurant().getOwner(), Kind.ORDER, "Order " + code + " cancelled",
							"The customer cancelled it before you accepted it. Reason: "
									+ Notification.sentence(o.getCancelReason()),
							"/owner/orders/" + code);
				}
			}
			case SYSTEM -> toCustomer(o, Kind.ORDER, "Payment time ran out",
					code + " from " + o.getRestaurant().getName() + " was cancelled because the payment wasn't completed in "
							+ Order.PAY_WITHIN.toMinutes() + " minutes. Nothing was charged.",
					"/orders/" + code);
			case ADMIN -> cancelledBySupport(o, kitchenHadIt);
			case RESTAURANT, RIDER -> {
				// they never cancel (OrderWorkflow)
			}
		}
	}

	private void cancelledBySupport(Order o, boolean kitchenHadIt) {
		String code = o.getCode();
		String kitchen = o.getRestaurant().getName();
		String reason = Notification.sentence(o.getCancelReason());
		String brand = app.brandName();
		String refund = refund(o);
		toCustomer(o, Kind.ORDER, brand + " support cancelled your order",
				code + " from " + kitchen + " was cancelled. Reason: " + reason + refund, "/orders/" + code);
		mailCustomer(o, "Your order " + code + " was cancelled", greeting(o.getCustomer())
				+ brand + " support cancelled your order " + code + " from " + kitchen + ".\n\n"
				+ "Reason: " + reason + "\n\n"
				+ (refund.isEmpty() ? "" : refund.strip() + "\n\n")
				+ "Order details: " + app.baseUrl() + "/orders/" + code + "\n\n"
				+ "Sorry about this. Questions? Write to support@zaika.test.\n\n"
				+ "Team " + brand + "\n");

		if (kitchenHadIt) {
			User owner = o.getRestaurant().getOwner();
			String body = code + " was cancelled by " + brand + " support. Reason: " + reason + " Stop preparing it"
					+ (o.getRider() != null ? "; its delivery partner has been told." : ".");
			notifications.notify(owner, Kind.ORDER, "Order " + code + " cancelled", body, "/owner/orders/" + code);
			AfterCommit.mail(mail, owner.getEmail(), "Order " + code + " was cancelled",
					greeting(owner) + body + "\n\nOrder: " + app.baseUrl() + "/owner/orders/" + code + "\n\nTeam "
							+ brand + "\n",
					"Support cancel email to the restaurant of " + code);
		}
		User rider = o.getRider();
		if (rider != null) {
			String body = code + " from " + kitchen + " was cancelled by " + brand + " support. Reason: " + reason
					+ " " + (o.timestampOf(OrderStatus.OUT_FOR_DELIVERY) != null
							? "Take the food back to " + kitchen + "." : "No need to pick it up.");
			notifications.notify(rider, Kind.DELIVERY, "Delivery " + code + " cancelled", body, "/partner");
			AfterCommit.mail(mail, rider.getEmail(), "Delivery " + code + " was cancelled",
					greeting(rider) + body + "\n\nYour dashboard: " + app.baseUrl() + "/partner\n\nTeam " + brand
							+ "\n",
					"Support cancel email to the rider of " + code);
		}
	}

	/** What happens to the money after a cancel or reject, as a sentence with a leading space; "" for cash orders. */
	private String refund(Order o) {
		if (o.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
			return " Your " + fmt.money(o.getBill().grandTotal())
					+ " will be refunded to your original payment method in 5-7 working days.";
		}
		if (o.getPaymentMethod() == PaymentMethod.ONLINE) {
			return " Nothing was charged.";
		}
		return "";
	}

	private void toCustomer(Order o, Kind kind, String title, String body, String link) {
		notifications.notify(o.getCustomer(), kind, title, body, link);
	}

	private void mailCustomer(Order o, String subject, String text) {
		AfterCommit.mail(mail, o.getCustomer().getEmail(), subject, text,
				"Order email \"" + subject + "\" for " + o.getCode());
	}

	private static String dishes(Order o) {
		int n = o.getItemCount();
		return n == 1 ? "1 dish" : n + " dishes";
	}

	private static String greeting(User user) {
		return "Hi " + user.firstName() + ",\n\n";
	}

	private String placedMail(Order o) {
		PriceBreakdown bill = o.getBill();
		StringBuilder text = new StringBuilder(greeting(o.getCustomer()));
		text.append("Thanks for ordering on ").append(app.brandName()).append("! ")
			.append(o.getRestaurant().getName())
			.append(" has your order and we'll let you know as soon as they accept it.\n\n");
		text.append("Order ").append(o.getCode()).append('\n');
		for (OrderItem item : o.getItems()) {
			text.append(item.getQuantity()).append(" × ").append(item.getName()).append("  ")
				.append(fmt.money(item.getLineTotal())).append('\n');
		}
		text.append("\nItem total  ").append(fmt.money(bill.itemTotal())).append('\n');
		if (bill.discount().signum() > 0) {
			text.append("Coupon ").append(o.getCouponCode()).append("  -").append(fmt.money(bill.discount()))
				.append('\n');
		}
		text.append("Delivery fee  ")
			.append(bill.deliveryFee().signum() == 0 ? "Free" : fmt.money(bill.deliveryFee()))
			.append('\n');
		text.append("Platform fee  ").append(fmt.money(bill.platformFee())).append('\n');
		text.append("GST  ").append(fmt.money(bill.gst())).append('\n');
		text.append("Total  ").append(fmt.money(bill.grandTotal())).append('\n');
		text.append(o.getPaymentMethod() == PaymentMethod.COD
				? "Cash on delivery: please keep " + fmt.money(bill.grandTotal()) + " ready.\n" : "Paid online.\n");
		text.append("\nDelivering to ").append(o.getAddress().oneLine()).append('\n');
		text.append("Expected by ").append(fmt.time(o.getEstimatedDeliveryAt())).append(".\n\n");
		text.append("Track your order: ").append(app.baseUrl()).append("/orders/").append(o.getCode())
			.append("/track\n\nTeam ").append(app.brandName()).append('\n');
		return text.toString();
	}

	private String deliveredMail(Order o) {
		return greeting(o.getCustomer()) + "Your order " + o.getCode() + " from " + o.getRestaurant().getName()
				+ " was delivered at " + fmt.time(o.timestampOf(OrderStatus.DELIVERED)) + ". Enjoy your meal!\n\n"
				+ "Total " + fmt.money(o.getBill().grandTotal()) + ", "
				+ (o.getPaymentMethod() == PaymentMethod.COD ? "paid in cash" : "paid online") + ".\n\n"
				+ "How was it? Rate your food and help others in Kolhapur pick their next meal:\n" + app.baseUrl()
				+ "/orders/" + o.getCode() + "#review\n\nTeam " + app.brandName() + "\n";
	}

}
