package com.zaika.order;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;
import com.zaika.order.PaymentReceipts.Receipt;

/**
 * What a customer sees of their own orders: the paginated history and one order's details. Only ever the signed-in
 * customer's orders; anyone else's code is a 404, the same as an unknown one.
 */
@Service
public class OrderHistoryService {

	/** Orders per history page. */
	public static final int PAGE_SIZE = 10;

	private final OrderRepository orders;

	private final PaymentReceipts payments;

	private final Clock clock;

	OrderHistoryService(OrderRepository orders, PaymentReceipts payments, Clock clock) {
		this.orders = orders;
		this.payments = payments;
		this.clock = clock;
	}

	/**
	 * Page {@code page} (0-based) of the customer's orders, newest first, with restaurants and dishes loaded: three
	 * queries (count, page, dishes) whatever the page holds.
	 */
	@Transactional(readOnly = true)
	public Page<Order> history(long customerId, int page) {
		Page<Order> result = orders.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId,
				PageRequest.of(Math.max(page, 0), PAGE_SIZE));
		if (result.hasContent()) {
			orders.fetchItems(result.map(Order::getId).toList());
		}
		return result;
	}

	/** The customer's order with restaurant, dishes and (online orders) the payment that paid it. */
	@Transactional(readOnly = true)
	public Detail detail(long customerId, String code) {
		Order order = orders.findByCodeAndCustomerId(code, customerId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for customer " + customerId));
		Receipt payment = order.getPaymentMethod() == PaymentMethod.ONLINE
				? payments.paidWith(order.getId()).orElse(null)
				: null;
		return new Detail(order, payment, LocalDateTime.now(clock));
	}

	/** An order page's data, as of {@code now}. {@code payment} is the successful online payment, or null. */
	public record Detail(Order order, Receipt payment, LocalDateTime now) {

		/** Still waiting for an online payment: the page offers to finish it. */
		public boolean awaitingPayment() {
			return order.getStatus() == OrderStatus.PENDING_PAYMENT;
		}

		/** When an unpaid order is cancelled automatically. */
		public LocalDateTime payBy() {
			return order.payBy();
		}

		/** Awaiting payment past its deadline: the expiry job cancels it any moment, so paying is no longer offered. */
		public boolean paymentTimeUp() {
			return awaitingPayment() && !now.isBefore(payBy());
		}

		/** On its way through the kitchen and to the door. */
		public boolean live() {
			return !awaitingPayment() && !order.getStatus().isFinal();
		}

		/** Who ended a cancelled or rejected order, as a sentence; null for any other order. */
		public String endedBy() {
			if (order.getStatus() == OrderStatus.REJECTED) {
				return order.getRestaurant().getName() + " could not take this order.";
			}
			if (order.getStatus() != OrderStatus.CANCELLED || order.getCancelledBy() == null) {
				return null;
			}
			return switch (order.getCancelledBy()) {
				case CUSTOMER -> "You cancelled this order.";
				case ADMIN -> "Zaika support cancelled this order.";
				case RESTAURANT -> order.getRestaurant().getName() + " cancelled this order.";
				case RIDER, SYSTEM -> "We cancelled this order.";
			};
		}

	}

}
