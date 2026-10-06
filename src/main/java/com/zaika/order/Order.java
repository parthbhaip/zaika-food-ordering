package com.zaika.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;

import com.zaika.cart.CartService;
import com.zaika.cart.PriceBreakdown;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.Restaurant;
import com.zaika.common.BaseEntity;
import com.zaika.user.Role;
import com.zaika.user.User;

/**
 * A customer's order from one restaurant, with everything snapshotted at checkout: the delivery address, the dishes
 * ({@link OrderItem}), the bill and the coupon code. Starts at {@link OrderStatus#PENDING_PAYMENT}; every status
 * change after that goes through {@link OrderWorkflow}, which also sets that status's timestamp. Optimistic locking
 * ({@code version}) makes two people acting on one order at once fail cleanly instead of overwriting each other.
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

	/** Most a cancel or reject reason may hold. */
	public static final int MAX_REASON = 500;

	/** How long an online order waits for its payment before it is cancelled. */
	public static final Duration PAY_WITHIN = Duration.ofMinutes(15);

	private static final SecureRandom OTP_RANDOM = new SecureRandom();

	/** "ZK-26-000123": assigned from the id right after the insert. */
	@Column(length = 20, unique = true)
	private String code;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "customer_id", nullable = false, updatable = false)
	private User customer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "rider_id")
	private User rider;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrderStatus status;

	@Embedded
	private DeliveryAddress address;

	@Column(name = "restaurant_note", length = CartService.MAX_INSTRUCTIONS)
	private String restaurantNote;

	@Column(name = "delivery_note", length = CartService.MAX_INSTRUCTIONS)
	private String deliveryNote;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<OrderItem> items = new ArrayList<>();

	@Column(name = "item_total", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal itemTotal;

	@Column(nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal discount;

	@Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal deliveryFee;

	@Column(name = "platform_fee", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal platformFee;

	@Column(nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal gst;

	@Column(name = "grand_total", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal grandTotal;

	@Column(name = "coupon_code", length = 20, updatable = false)
	private String couponCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, length = 10)
	private PaymentMethod paymentMethod;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_status", nullable = false, length = 20)
	private PaymentStatus paymentStatus;

	/** The 4 digits the customer tells the rider at the door. Never logged. */
	@Column(name = "delivery_otp", nullable = false, length = 4, updatable = false)
	private String deliveryOtp;

	@Column(name = "estimated_delivery_at", nullable = false)
	private LocalDateTime estimatedDeliveryAt;

	@Column(name = "placed_at")
	private LocalDateTime placedAt;

	@Column(name = "accepted_at")
	private LocalDateTime acceptedAt;

	@Column(name = "preparing_at")
	private LocalDateTime preparingAt;

	@Column(name = "ready_at")
	private LocalDateTime readyAt;

	@Column(name = "out_for_delivery_at")
	private LocalDateTime outForDeliveryAt;

	@Column(name = "delivered_at")
	private LocalDateTime deliveredAt;

	@Column(name = "cancelled_at")
	private LocalDateTime cancelledAt;

	@Column(name = "rejected_at")
	private LocalDateTime rejectedAt;

	/** Why the order was cancelled or rejected. */
	@Column(name = "cancel_reason", length = MAX_REASON)
	private String cancelReason;

	/** Who cancelled or rejected it. */
	@Enumerated(EnumType.STRING)
	@Column(name = "cancelled_by", length = 20)
	private OrderActor.Kind cancelledBy;

	/** The checkout form's one-time key: a second submit of the same form finds this order instead of a new one. */
	@Column(name = "idempotency_key", length = 36, updatable = false)
	private String idempotencyKey;

	protected Order() {
	}

	/**
	 * A new order awaiting payment, priced by {@code bill} (from {@code PricingService}). Add the dishes with
	 * {@link #addItem}; a cash order is then moved to PLACED through the workflow.
	 *
	 * @param couponCode the redeemed coupon's code, or {@code null}
	 */
	public Order(User customer, Restaurant restaurant, DeliveryAddress address, PaymentMethod paymentMethod,
			PriceBreakdown bill, String couponCode, LocalDateTime estimatedDeliveryAt) {
		this.customer = Objects.requireNonNull(customer, "customer");
		if (customer.getRole() != Role.CUSTOMER) {
			throw new IllegalArgumentException("Only customers place orders");
		}
		this.restaurant = Objects.requireNonNull(restaurant, "restaurant");
		this.address = Objects.requireNonNull(address, "address");
		this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
		this.itemTotal = money(bill.itemTotal());
		this.discount = money(bill.discount());
		this.deliveryFee = money(bill.deliveryFee());
		this.platformFee = money(bill.platformFee());
		this.gst = money(bill.gst());
		this.grandTotal = money(bill.grandTotal());
		this.couponCode = couponCode;
		this.estimatedDeliveryAt = Objects.requireNonNull(estimatedDeliveryAt, "estimatedDeliveryAt");
		this.status = OrderStatus.PENDING_PAYMENT;
		this.paymentStatus = PaymentStatus.PENDING;
		this.deliveryOtp = "%04d".formatted(OTP_RANDOM.nextInt(10_000));
	}

	/** Copies {@code menuItem}'s name, current price and veg mark as a line of {@code quantity} (1-20). */
	public OrderItem addItem(MenuItem menuItem, int quantity) {
		if (!menuItem.getRestaurant().equals(restaurant)) {
			throw new IllegalArgumentException("A dish from another restaurant");
		}
		if (quantity < 1 || quantity > CartService.MAX_QUANTITY) {
			throw new IllegalArgumentException("Quantity out of range: " + quantity);
		}
		OrderItem item = new OrderItem(this, menuItem, quantity);
		items.add(item);
		return item;
	}

	@PostPersist
	void assignCode() {
		if (code == null) {
			code = "ZK-%02d-%06d".formatted(getCreatedAt().getYear() % 100, getId());
		}
	}

	/** Moves to {@code target} and stamps its timestamp. Only {@link OrderWorkflow} calls this. */
	void moveTo(OrderStatus target, LocalDateTime at) {
		switch (target) {
			case PENDING_PAYMENT -> throw new IllegalArgumentException("No order goes back to awaiting payment");
			case PLACED -> placedAt = at;
			case ACCEPTED -> acceptedAt = at;
			case PREPARING -> preparingAt = at;
			case READY_FOR_PICKUP -> readyAt = at;
			case OUT_FOR_DELIVERY -> outForDeliveryAt = at;
			case DELIVERED -> deliveredAt = at;
			case CANCELLED -> cancelledAt = at;
			case REJECTED -> rejectedAt = at;
		}
		status = target; // only once the move is known to be possible
	}

	/** When an unpaid online order is cancelled automatically: {@link #PAY_WITHIN} after checkout. */
	public LocalDateTime payBy() {
		return getCreatedAt().plus(PAY_WITHIN);
	}

	void recordCancellation(String reason, OrderActor.Kind by) {
		this.cancelReason = reason;
		this.cancelledBy = by;
	}

	void setPaymentStatus(PaymentStatus paymentStatus) {
		this.paymentStatus = Objects.requireNonNull(paymentStatus, "paymentStatus");
	}

	/** An online payment attempt failed: the order keeps waiting for payment (retry, cash or cancel). */
	public void paymentFailed() {
		requireAwaitingOnlinePayment();
		this.paymentStatus = PaymentStatus.FAILED;
	}

	/**
	 * The customer gives up paying online and pays the rider instead. The caller then places the order through the
	 * workflow, as checkout does for cash orders.
	 */
	public void switchToCashOnDelivery() {
		requireAwaitingOnlinePayment();
		this.paymentMethod = PaymentMethod.COD;
		this.paymentStatus = PaymentStatus.PENDING;
	}

	private void requireAwaitingOnlinePayment() {
		if (status != OrderStatus.PENDING_PAYMENT || paymentMethod != PaymentMethod.ONLINE) {
			throw new IllegalStateException("Order " + code + " is not awaiting an online payment");
		}
	}

	/**
	 * Gives the order to a rider: not a status change, so not the workflow's job. The rider panel's claim (T35) calls
	 * this under optimistic locking; only the assigned rider can then pick the order up and deliver it.
	 */
	public void assignRider(User rider) {
		if (rider.getRole() != Role.DELIVERY_PARTNER) {
			throw new IllegalArgumentException("Only delivery partners deliver orders");
		}
		this.rider = rider;
	}

	/** When the order reached {@code status}, or {@code null} if it has not (awaiting payment = creation time). */
	public LocalDateTime timestampOf(OrderStatus status) {
		return switch (status) {
			case PENDING_PAYMENT -> getCreatedAt();
			case PLACED -> placedAt;
			case ACCEPTED -> acceptedAt;
			case PREPARING -> preparingAt;
			case READY_FOR_PICKUP -> readyAt;
			case OUT_FOR_DELIVERY -> outForDeliveryAt;
			case DELIVERED -> deliveredAt;
			case CANCELLED -> cancelledAt;
			case REJECTED -> rejectedAt;
		};
	}

	/** The bill as it was at checkout. */
	public PriceBreakdown getBill() {
		return new PriceBreakdown(itemTotal, discount, deliveryFee, platformFee, gst, grandTotal);
	}

	/** Number of dishes (sum of quantities). */
	public int getItemCount() {
		return items.stream().mapToInt(OrderItem::getQuantity).sum();
	}

	/** Set once, at checkout, before the order is saved. */
	public void setIdempotencyKey(String idempotencyKey) {
		if (this.idempotencyKey != null) {
			throw new IllegalStateException("The checkout key is already set");
		}
		this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setRestaurantNote(String note) {
		this.restaurantNote = note(note);
	}

	public void setDeliveryNote(String note) {
		this.deliveryNote = note(note);
	}

	/** The restaurant's new estimate (for example when it accepts with its own preparation time). */
	public void setEstimatedDeliveryAt(LocalDateTime estimatedDeliveryAt) {
		this.estimatedDeliveryAt = Objects.requireNonNull(estimatedDeliveryAt, "estimatedDeliveryAt");
	}

	private static String note(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		String stripped = note.strip();
		if (stripped.length() > CartService.MAX_INSTRUCTIONS) {
			throw new IllegalArgumentException("Note longer than " + CartService.MAX_INSTRUCTIONS);
		}
		return stripped;
	}

	private static BigDecimal money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP);
	}

	public String getCode() {
		return code;
	}

	public User getCustomer() {
		return customer;
	}

	public Restaurant getRestaurant() {
		return restaurant;
	}

	public User getRider() {
		return rider;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public DeliveryAddress getAddress() {
		return address;
	}

	public String getRestaurantNote() {
		return restaurantNote;
	}

	public String getDeliveryNote() {
		return deliveryNote;
	}

	public List<OrderItem> getItems() {
		return Collections.unmodifiableList(items);
	}

	public String getCouponCode() {
		return couponCode;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public PaymentStatus getPaymentStatus() {
		return paymentStatus;
	}

	public String getDeliveryOtp() {
		return deliveryOtp;
	}

	public LocalDateTime getEstimatedDeliveryAt() {
		return estimatedDeliveryAt;
	}

	public String getCancelReason() {
		return cancelReason;
	}

	public OrderActor.Kind getCancelledBy() {
		return cancelledBy;
	}

}
