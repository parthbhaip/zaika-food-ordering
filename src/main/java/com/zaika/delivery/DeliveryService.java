package com.zaika.delivery;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.zaika.catalog.Restaurant;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.config.AppProperties;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentStatus;

/**
 * The rider panel's rules: going online, the orders waiting for a rider, claiming one and the rider's current
 * delivery. Claiming is not a status change ({@link Order#assignRider}), so it does not go through the workflow; it
 * relies on the order's {@code version}: when two riders claim the same order at once, the second write fails and
 * that rider is told someone else took it. A rider's own claims are serialized by a lock on their profile row, so
 * nobody ends up with two live deliveries. Picking up and delivering are status changes made through the workflow
 * under a row lock on the order; delivering needs the customer's code and records what the rider earned.
 */
@Service
public class DeliveryService {

	/** Orders a rider can take: the kitchen has accepted them and nobody is assigned yet. */
	public static final Set<OrderStatus> AVAILABLE = Set.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING,
			OrderStatus.READY_FOR_PICKUP);

	/** A rider's delivery is live from the claim until it is delivered (or cancelled). */
	public static final Set<OrderStatus> ACTIVE = Set.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING,
			OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY);

	private final DeliveryPartnerProfileRepository profiles;

	private final OrderRepository orders;

	private final AppProperties app;

	private final TransactionTemplate tx;

	private final OrderWorkflow workflow;

	private final RiderEarningRepository earnings;

	private final Formatters fmt;

	private final Clock clock;

	DeliveryService(DeliveryPartnerProfileRepository profiles, OrderRepository orders, AppProperties app,
			TransactionTemplate tx, OrderWorkflow workflow, RiderEarningRepository earnings, Formatters fmt,
			Clock clock) {
		this.profiles = profiles;
		this.orders = orders;
		this.app = app;
		this.tx = tx;
		this.workflow = workflow;
		this.earnings = earnings;
		this.fmt = fmt;
		this.clock = clock;
	}

	/**
	 * The rider's dashboard: their profile, their live delivery (if any) and, when they are online with nothing on
	 * their hands, the oldest orders waiting for a rider. Empty for a rider account without a profile. At most five
	 * statements whatever the volume.
	 */
	@Transactional(readOnly = true)
	public Optional<Dashboard> dashboard(long riderId) {
		return profiles.findByUserId(riderId).map(profile -> {
			Order active = orders.findByRiderIdAndStatusIn(riderId, ACTIVE).stream().findFirst().orElse(null);
			List<Offer> offers = List.of();
			if (active == null && profile.isOnline()) {
				List<Order> waiting = orders.findTop20ByRiderIsNullAndStatusInOrderByPlacedAtAscIdAsc(AVAILABLE);
				if (!waiting.isEmpty()) {
					orders.fetchItems(waiting.stream().map(Order::getId).toList()); // item counts, one query
				}
				offers = waiting.stream().map(o -> new Offer(o, payout(o))).toList();
			}
			return new Dashboard(profile, active, active == null ? null : payout(active), offers);
		});
	}

	/**
	 * Goes online or offline. Going offline never drops a delivery the rider already has.
	 *
	 * @throws DeliveryRuleException if a rider who is not approved tries to go online
	 * @throws NotFoundException     if the account has no rider profile
	 */
	@Transactional
	public DeliveryPartnerProfile setOnline(long riderId, boolean online) {
		DeliveryPartnerProfile profile = profile(riderId);
		if (online) {
			requireApproved(profile, "go online");
		}
		profile.setOnline(online);
		return profile;
	}

	/**
	 * Gives the order to the rider. If another write to the order got there first (a second rider claiming it, the
	 * kitchen marking it ready), the claim is tried once more against the fresh order, which then either succeeds or
	 * says who was faster.
	 *
	 * @throws DeliveryRuleException if the rider cannot take it (not approved, offline, already delivering) or it is
	 *                               no longer available
	 * @throws NotFoundException     if there is no such placed order, or the account has no rider profile
	 */
	public Order claim(long riderId, String code) {
		try {
			return tx.execute(status -> claimOnce(riderId, code));
		}
		catch (ConcurrencyFailureException first) {
			try {
				return tx.execute(status -> claimOnce(riderId, code));
			}
			catch (ConcurrencyFailureException again) {
				throw new DeliveryRuleException(taken(code));
			}
		}
	}

	private Order claimOnce(long riderId, String code) {
		DeliveryPartnerProfile profile = profiles.findForUpdateByUserId(riderId)
			.orElseThrow(() -> new NotFoundException("No rider profile for user " + riderId));
		requireApproved(profile, "take orders");
		if (!profile.isOnline()) {
			throw new DeliveryRuleException("Go online to take orders.");
		}
		Order order = orders.findByCode(code)
			.filter(o -> o.timestampOf(OrderStatus.PLACED) != null)
			.orElseThrow(() -> new NotFoundException("Order " + code));
		if (order.getRider() != null && order.getRider().getId() == riderId) {
			throw new DeliveryRuleException(code + " is already yours.");
		}
		if (orders.existsByRiderIdAndStatusIn(riderId, ACTIVE)) {
			throw new DeliveryRuleException("Finish your current delivery before you take another order.");
		}
		if (order.getStatus() == OrderStatus.PLACED) {
			throw new DeliveryRuleException("The kitchen hasn't accepted " + code + " yet.");
		}
		if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REJECTED) {
			throw new DeliveryRuleException(code + " was cancelled, so there's nothing to pick up.");
		}
		if (order.getRider() != null || !AVAILABLE.contains(order.getStatus())) {
			throw new DeliveryRuleException(taken(code));
		}
		order.assignRider(profile.getUser());
		return orders.saveAndFlush(order); // the version check happens here
	}

	/**
	 * The rider has the food: READY_FOR_PICKUP -> OUT_FOR_DELIVERY. Works whatever the rider's online status, so a
	 * delivery already on the way can always be finished.
	 *
	 * @throws DeliveryRuleException if the order is not ready yet, already picked up, delivered or cancelled
	 * @throws NotFoundException     if there is no such order or it is not assigned to this rider
	 */
	@Transactional
	public Order pickUp(long riderId, String code) {
		Order order = assigned(riderId, code);
		switch (order.getStatus()) {
			case READY_FOR_PICKUP -> {
			}
			case ACCEPTED, PREPARING ->
				throw new DeliveryRuleException("The kitchen hasn't marked " + code + " ready yet. Pick it up once it's packed.");
			case OUT_FOR_DELIVERY -> throw new DeliveryRuleException(code + " is already picked up.");
			default -> throw moved(order, "so there's nothing to pick up.");
		}
		return workflow.transition(order, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(riderId));
	}

	/**
	 * Hands the order over: checks the customer's 4-digit code ({@code otp}, spaces ignored), then OUT_FOR_DELIVERY
	 * -> DELIVERED (a cash order becomes PAID) and records what the rider earned and the cash they collected. A wrong
	 * code is counted and committed; the fifth in a row locks the code for {@link Order#OTP_LOCK}.
	 *
	 * @return the rider's earning for this delivery
	 * @throws OtpException          if the code is badly formed, wrong or locked (message for the code field)
	 * @throws DeliveryRuleException if the order is not out for delivery (not picked up yet, delivered, cancelled)
	 * @throws NotFoundException     if there is no such order or it is not assigned to this rider
	 */
	@Transactional(noRollbackFor = OtpException.class)
	public RiderEarning deliver(long riderId, String code, String otp) {
		Order order = assigned(riderId, code);
		switch (order.getStatus()) {
			case OUT_FOR_DELIVERY -> {
			}
			case ACCEPTED, PREPARING, READY_FOR_PICKUP ->
				throw new DeliveryRuleException("Mark " + code + " picked up before you deliver it.");
			default -> throw moved(order, "so there's nothing to deliver. Take the food back to "
					+ order.getRestaurant().getName() + ".");
		}
		String typed = otp == null ? "" : StringUtils.trimAllWhitespace(otp);
		if (!typed.matches("[0-9]{4}")) {
			throw new OtpException("Enter the 4-digit code from the customer's tracking page.");
		}
		LocalDateTime now = LocalDateTime.now(clock);
		if (order.otpLockedAt(now)) {
			throw new OtpException("Too many wrong codes. You can try again at " + fmt.time(order.getOtpLockedUntil())
					+ ". Call the customer to check the code.");
		}
		if (!order.checkDeliveryOtp(typed, now)) {
			if (order.otpLockedAt(now)) {
				throw new OtpException("That's " + Order.MAX_OTP_ATTEMPTS + " wrong codes, so the code is locked until "
						+ fmt.time(order.getOtpLockedUntil()) + ". Call the customer to check the code.");
			}
			int left = order.otpAttemptsLeft();
			throw new OtpException("That code doesn't match. " + left + (left == 1 ? " try" : " tries") + " left.");
		}
		boolean cash = order.getPaymentMethod() == PaymentMethod.COD
				&& order.getPaymentStatus() != PaymentStatus.PAID;
		BigDecimal collected = cash ? order.getBill().grandTotal() : BigDecimal.ZERO;
		workflow.transition(order, OrderStatus.DELIVERED, OrderActor.rider(riderId));
		BigDecimal feeShare = app.riderDeliveryFeeShare().multiply(order.getBill().deliveryFee());
		return earnings.save(new RiderEarning(order.getRider(), order, app.riderBasePayout(), feeShare, collected, now));
	}

	/** The order with a row lock, if it is assigned to this rider; anyone else's (or an unknown code) is a 404. */
	private Order assigned(long riderId, String code) {
		return orders.findForUpdateByCode(code)
			.filter(o -> o.getRider() != null && o.getRider().getId() == riderId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for rider " + riderId));
	}

	/** For an order that is delivered or was cancelled while the rider had it; {@code cancelled} ends the message. */
	private static DeliveryRuleException moved(Order order, String cancelled) {
		return new DeliveryRuleException(order.getStatus() == OrderStatus.DELIVERED
				? order.getCode() + " is already delivered."
				: order.getCode() + " was cancelled, " + cancelled);
	}

	/** What the rider earns for delivering {@code order}: the base payout plus their share of its delivery fee. */
	public BigDecimal payout(Order order) {
		return payout(app.riderBasePayout(), app.riderDeliveryFeeShare(), order.getBill().deliveryFee());
	}

	/** {@code base + share x deliveryFee}, to the paisa (HALF_UP). */
	static BigDecimal payout(BigDecimal base, BigDecimal share, BigDecimal deliveryFee) {
		return base.add(share.multiply(deliveryFee)).setScale(2, RoundingMode.HALF_UP);
	}

	private static String taken(String code) {
		return "Another partner just took " + code + ". Pick another order.";
	}

	private DeliveryPartnerProfile profile(long riderId) {
		return profiles.findByUserId(riderId)
			.orElseThrow(() -> new NotFoundException("No rider profile for user " + riderId));
	}

	/** {@code action} completes the sentence: "go online", "take orders". */
	private static void requireApproved(DeliveryPartnerProfile profile, String action) {
		switch (profile.getStatus()) {
			case APPROVED -> {
			}
			case PENDING -> throw new DeliveryRuleException("You can " + action + " once your account is approved.");
			case SUSPENDED -> throw new DeliveryRuleException("Your account is suspended, so you can't " + action + ".");
		}
	}

	/** An order waiting for a rider, with what delivering it pays. */
	public record Offer(Order order, BigDecimal payout) {
	}

	/**
	 * The rider's dashboard: profile (with account), the live delivery or {@code null}, what it pays, and the orders
	 * waiting for a rider (empty when offline or busy).
	 */
	public record Dashboard(DeliveryPartnerProfile profile, Order active, BigDecimal activePayout,
			List<Offer> offers) {

		/** Approved and online: the list of waiting orders is shown. */
		public boolean onDuty() {
			return profile.getStatus() == RiderStatus.APPROVED && profile.isOnline();
		}

		/**
		 * A fingerprint of the waiting orders (id and version each), for the 15-second poll: it changes when an order
		 * joins or leaves the list or any of them changes (status, ETA).
		 */
		public String version() {
			StringBuilder all = new StringBuilder();
			offers.forEach(o -> all.append('|').append(o.order().getId()).append(':').append(o.order().getVersion()));
			CRC32 crc = new CRC32();
			crc.update(all.toString().getBytes(StandardCharsets.US_ASCII));
			return Long.toString(crc.getValue(), 36) + "-" + offers.size();
		}

		/**
		 * The live delivery's code and version, for its poll while the kitchen is cooking: it changes when the kitchen
		 * marks it ready or it is cancelled. {@code null} without a live delivery.
		 */
		public String deliveryVersion() {
			return active == null ? null : active.getCode() + ":" + active.getVersion();
		}

		/** Cash the rider collects at the door for a cash order not paid yet, else {@code null}. */
		public BigDecimal cashToCollect() {
			return active != null && active.getPaymentMethod() == PaymentMethod.COD
					&& active.getPaymentStatus() != PaymentStatus.PAID ? active.getBill().grandTotal() : null;
		}

		/** When the kitchen should have the food packed: the customer's ETA minus the ride. */
		public LocalDateTime readyBy(Order order) {
			return order.getEstimatedDeliveryAt().minusMinutes(Restaurant.RIDE_MINUTES);
		}

	}

}
