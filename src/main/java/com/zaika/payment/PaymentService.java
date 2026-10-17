package com.zaika.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.common.NotFoundException;
import com.zaika.coupon.CouponService;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentReceipts;

/**
 * Online payment for orders that checkout left at PENDING_PAYMENT. The customer pays through the
 * {@link PaymentGateway} (success: PLACED and PAID; decline: the order keeps waiting, payment FAILED), switches to
 * cash on delivery (PLACED straight away), or cancels. An order not paid within {@link Order#PAY_WITHIN} is cancelled by
 * the system, by {@link #expireUnpaid()} every minute, and also on the spot whenever its payment page is used after
 * the deadline. A cancelled or expired order gives its coupon use back.
 *
 * <p>Every action locks the order row first, so a payment, a cancel and the expiry job never act on one order at once.
 * Customers only ever see their own orders: anyone else's code is a 404.
 */
@Service
public class PaymentService implements PaymentReceipts {

	static final String EXPIRED_REASON = "Not paid within 15 minutes";

	static final String CANCELLED_REASON = "Cancelled before paying";

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final OrderRepository orders;

	private final PaymentRepository payments;

	private final PaymentGateway gateway;

	private final OrderWorkflow workflow;

	private final CouponService coupons;

	private final TransactionTemplate tx;

	private final Clock clock;

	PaymentService(OrderRepository orders, PaymentRepository payments, PaymentGateway gateway, OrderWorkflow workflow,
			CouponService coupons, TransactionTemplate tx, Clock clock) {
		this.orders = orders;
		this.payments = payments;
		this.gateway = gateway;
		this.workflow = workflow;
		this.coupons = coupons;
		this.tx = tx;
		this.clock = clock;
	}

	/** The customer's order for the payment page (expired first if its time is up), with its latest attempt. */
	@Transactional
	public PayPage page(long customerId, String code) {
		Order order = own(customerId, code);
		expireIfDue(order);
		order.getItems().size(); // loads the dishes (getItems wraps the lazy list)
		return new PayPage(order, deadline(order), payments.findFirstByOrderIdOrderByIdDesc(order.getId()).orElse(null));
	}

	/**
	 * Charges the order's total through the gateway with what the customer typed ({@code form} must be valid).
	 *
	 * @return PAID (the order went to the kitchen), DECLINED (still awaiting payment), or CLOSED when the order was no
	 *         longer awaiting an online payment (paid, switched to cash, cancelled, or its time just ran out)
	 */
	@Transactional
	public PayResult pay(long customerId, String code, PaymentForm form) {
		Order order = own(customerId, code);
		if (!awaitingOnlinePayment(order)) {
			return new PayResult(order, PayResult.Kind.CLOSED);
		}
		PaymentGateway.Charge charge = new PaymentGateway.Charge(order.getCode(), order.getBill().grandTotal(),
				form.getChannel(), form.maskedInstrument(), form.isSimulateFailure());
		PaymentGateway.Result result = gateway.charge(charge);
		payments.save(new Payment(order, gateway.name(), charge, result));
		if (result.succeeded()) {
			workflow.confirmPayment(order);
			return new PayResult(order, PayResult.Kind.PAID);
		}
		order.paymentFailed();
		return new PayResult(order, PayResult.Kind.DECLINED);
	}

	/** Pay the rider in cash instead: the order goes to the kitchen now. False if it was no longer awaiting payment. */
	@Transactional
	public boolean switchToCash(long customerId, String code) {
		Order order = own(customerId, code);
		if (!awaitingOnlinePayment(order)) {
			return false;
		}
		order.switchToCashOnDelivery();
		workflow.transition(order, OrderStatus.PLACED, OrderActor.system());
		return true;
	}

	/** The customer cancels an unpaid order. False if it was no longer awaiting payment. */
	@Transactional
	public boolean cancel(long customerId, String code) {
		Order order = own(customerId, code);
		if (!awaitingOnlinePayment(order)) {
			return false;
		}
		workflow.transition(order, OrderStatus.CANCELLED, OrderActor.customer(customerId), CANCELLED_REASON);
		coupons.release(order);
		return true;
	}

	/**
	 * Cancels every order still unpaid {@link Order#PAY_WITHIN} after checkout. Each order gets its own transaction and
	 * row lock, so one failure does not stop the rest and a payment arriving at the same moment wins or loses cleanly.
	 * Runs every minute ({@code app.payments.expiry-cron}; "-" switches it off, as the tests do).
	 *
	 * @return how many orders were cancelled
	 */
	@Scheduled(cron = "${app.payments.expiry-cron}")
	public int expireUnpaid() {
		List<Long> due = orders.findIdsByStatusCreatedUpTo(OrderStatus.PENDING_PAYMENT,
				LocalDateTime.now(clock).minus(Order.PAY_WITHIN));
		int expired = 0;
		for (Long id : due) {
			try {
				if (Boolean.TRUE.equals(tx.execute(status -> orders.findForUpdate(id).map(this::expireIfDue)
					.orElse(false)))) {
					expired++;
				}
			}
			catch (RuntimeException ex) {
				log.warn("Could not expire unpaid order {}", id, ex);
			}
		}
		if (expired > 0) {
			log.info("Cancelled {} unpaid order(s) after {} minutes", expired, Order.PAY_WITHIN.toMinutes());
		}
		return expired;
	}

	/** When an unpaid order is cancelled automatically. */
	public LocalDateTime deadline(Order order) {
		return order.payBy();
	}

	@Override
	@Transactional(readOnly = true)
	public List<Attempt> attempts(long orderId) {
		return payments.findByOrderIdOrderByIdAsc(orderId)
			.stream()
			.map(p -> new Attempt(p.getCreatedAt(), p.getStatus() == Payment.Status.SUCCEEDED, p.getChannel().getLabel(),
					p.getInstrument(), p.getAmount(), p.getGatewayReference(), p.getFailureReason()))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Receipt> paidWith(long orderId) {
		return payments.findFirstByOrderIdAndStatusOrderByIdDesc(orderId, Payment.Status.SUCCEEDED)
			.map(payment -> new Receipt(payment.getInstrument(), payment.getGatewayReference()));
	}

	/** Still awaiting an online payment, after expiring it if its time is up. */
	private boolean awaitingOnlinePayment(Order order) {
		return !expireIfDue(order) && order.getStatus() == OrderStatus.PENDING_PAYMENT
				&& order.getPaymentMethod() == PaymentMethod.ONLINE;
	}

	/** Cancels {@code order} (and releases its coupon use) if it is unpaid and past its deadline. */
	private boolean expireIfDue(Order order) {
		if (order.getStatus() != OrderStatus.PENDING_PAYMENT || LocalDateTime.now(clock).isBefore(deadline(order))) {
			return false;
		}
		workflow.transition(order, OrderStatus.CANCELLED, OrderActor.system(), EXPIRED_REASON);
		coupons.release(order);
		return true;
	}

	/** The customer's order by code, row-locked, restaurant loaded; anyone else's (or an unknown code) is a 404. */
	private Order own(long customerId, String code) {
		Order order = orders.findForUpdateByCode(code)
			.filter(o -> o.getCustomer().getId() == customerId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for customer " + customerId));
		Hibernate.initialize(order.getRestaurant());
		return order;
	}

	/** The payment page's data: the order (restaurant and dishes loaded), its deadline and its latest attempt. */
	public record PayPage(Order order, LocalDateTime deadline, Payment lastAttempt) {

		/** Show the payment form. */
		public boolean awaitingPayment() {
			return order.getStatus() == OrderStatus.PENDING_PAYMENT;
		}

		/** Cancelled before it ever reached the kitchen (by the customer, or because time ran out). */
		public boolean cancelledUnpaid() {
			return order.getStatus() == OrderStatus.CANCELLED && order.timestampOf(OrderStatus.PLACED) == null;
		}

		/** Cancelled because time ran out (rather than by the customer). */
		public boolean expired() {
			return cancelledUnpaid() && order.getCancelledBy() == OrderActor.Kind.SYSTEM;
		}

		/** The last attempt was declined: show why, and the ways out. */
		public boolean lastAttemptFailed() {
			return awaitingPayment() && lastAttempt != null && lastAttempt.getStatus() == Payment.Status.FAILED;
		}

	}

	/** What a payment attempt did to the order. */
	public record PayResult(Order order, Kind kind) {

		public enum Kind {
			PAID, DECLINED, CLOSED
		}

	}

}
