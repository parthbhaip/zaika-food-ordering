package com.zaika.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantRepository.Choice;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.coupon.CouponService;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.order.PaymentReceipts;
import com.zaika.order.PaymentReceipts.Attempt;
import com.zaika.user.User;

/**
 * Every order on Zaika for the admin: the filtered list, one order with its full timeline (checkout, each payment
 * attempt, every status it reached, how it ended), and the admin's cancel. A cancel goes through
 * {@link OrderWorkflow} under the order's row lock, gives the coupon use back, and a paid online order moves to
 * REFUND_PENDING (the workflow's rule).
 */
@Service
public class OrderAdminService {

	public static final int PAGE_SIZE = 20;

	private final OrderRepository orders;

	private final RestaurantRepository restaurants;

	private final PaymentReceipts payments;

	private final OrderWorkflow workflow;

	private final CouponService coupons;

	private final Clock clock;

	private final Formatters fmt;

	OrderAdminService(OrderRepository orders, RestaurantRepository restaurants, PaymentReceipts payments,
			OrderWorkflow workflow, CouponService coupons, Clock clock, Formatters fmt) {
		this.orders = orders;
		this.restaurants = restaurants;
		this.payments = payments;
		this.workflow = workflow;
		this.coupons = coupons;
		this.clock = clock;
		this.fmt = fmt;
	}

	/** Every restaurant by name: the restaurant filter's options. */
	@Transactional(readOnly = true)
	public List<Choice> restaurants() {
		return restaurants.findChoices();
	}

	/** Today in the app's time zone (date filters). */
	public LocalDate today() {
		return LocalDate.now(clock);
	}

	/** One page of the matching orders, newest checkout first, restaurant and customer loaded. */
	@Transactional(readOnly = true)
	public Page<Order> list(OrderAdminFilter filter) {
		String pattern = filter.getQ() == null ? null : RestaurantBrowseService.likePattern(filter.getQ());
		return orders.findForAdmin(pattern, filter.getStatus(),
				filter.getRestaurant() == null ? null : filter.getRestaurant().id(), filter.fromTime(), filter.toTime(),
				PageRequest.of(filter.getPage() - 1, PAGE_SIZE));
	}

	/** One order with everything loaded, its payment attempts and timeline, as {@code adminId} sees it now. */
	@Transactional(readOnly = true)
	public Detail detail(String code, long adminId) {
		Order order = orders.findDetailByCode(code).orElseThrow(() -> new NotFoundException("No order " + code));
		// a cash order may have failed online attempts before the customer switched to cash
		List<Attempt> attempts = payments.attempts(order.getId());
		boolean canCancel = workflow.can(order, OrderStatus.CANCELLED, OrderActor.admin(adminId));
		LocalDateTime now = LocalDateTime.now(clock);
		Detail draft = new Detail(order, attempts, List.of(), canCancel, now);
		return new Detail(order, attempts, timeline(draft), canCancel, now);
	}

	/**
	 * The admin cancels the order with {@code reason} (required, at most {@link Order#MAX_REASON} characters): the
	 * coupon use is given back and a paid online order goes to REFUND_PENDING.
	 *
	 * @return the cancelled order
	 * @throws NotFoundException   if there is no such order
	 * @throws ModerationException if the order has already ended (delivered, cancelled or rejected)
	 */
	@Transactional
	public Order cancel(String code, long adminId, String reason) {
		Order order = orders.findForUpdateByCode(code).orElseThrow(() -> new NotFoundException("No order " + code));
		OrderActor admin = OrderActor.admin(adminId);
		if (!workflow.can(order, OrderStatus.CANCELLED, admin)) {
			throw new ModerationException(refusal(order));
		}
		workflow.transition(order, OrderStatus.CANCELLED, admin, reason);
		coupons.release(order);
		return order;
	}

	/**
	 * Everything that happened, oldest first: checkout, each payment attempt, every status the order reached and
	 * how it ended. While the order is live the latest status is current and the expected delivery comes last.
	 */
	private List<Event> timeline(Detail d) {
		Order order = d.order();
		List<Event> events = new ArrayList<>();
		LocalDateTime start = order.getCreatedAt();
		int items = order.getItemCount();
		events.add(new Event(start, "Checked out", items + (items == 1 ? " item · " : " items · ")
				+ fmt.money(order.getBill().grandTotal()) + (order.getCouponCode() == null ? "" : " · coupon " + order.getCouponCode()),
				Event.Kind.DONE, "receipt", start));
		for (Attempt a : d.attempts()) {
			events.add(a.succeeded()
					? new Event(a.at(), "Paid online", a.channel() + " · " + a.instrument() + " · " + a.gatewayReference(),
							Event.Kind.DONE, "card", start)
					: new Event(a.at(), "Payment failed", a.channel() + " · " + a.instrument()
							+ (a.failureReason() == null ? "" : " · " + a.failureReason()), Event.Kind.FAILED, "card", start));
		}
		User rider = order.getRider();
		for (OrderStatus s : OrderStatus.values()) {
			LocalDateTime at = order.timestampOf(s);
			if (s == OrderStatus.PENDING_PAYMENT || at == null) {
				continue;
			}
			events.add(switch (s) {
				case PLACED -> new Event(at, "Sent to " + order.getRestaurant().getName(),
						order.getPaymentMethod() == PaymentMethod.COD ? "Cash on delivery" : "Paid online", Event.Kind.DONE,
						"store", start);
				case ACCEPTED -> new Event(at, "Accepted by the restaurant", null, Event.Kind.DONE, "check", start);
				case PREPARING -> new Event(at, "Being prepared", null, Event.Kind.DONE, "clock", start);
				case READY_FOR_PICKUP -> new Event(at, "Ready for pickup", null, Event.Kind.DONE, "store", start);
				case OUT_FOR_DELIVERY -> new Event(at, "Out for delivery",
						rider == null ? null : "Picked up by " + rider.getName(), Event.Kind.DONE, "bike", start);
				case DELIVERED -> new Event(at, "Delivered", order.getPaymentMethod() == PaymentMethod.COD
						? "Cash collected: " + fmt.money(order.getBill().grandTotal()) : null, Event.Kind.DONE, "location", start);
				default -> new Event(at, d.endedBy(), order.getCancelReason(), Event.Kind.FAILED, "x", start);
			});
		}
		events.sort(Comparator.comparing(Event::at)); // stable: checkout, then payments, then statuses
		if (d.live()) {
			Event latest = events.removeLast();
			events.add(new Event(latest.at(), latest.title(), latest.note(), Event.Kind.CURRENT, latest.icon(), start));
			LocalDateTime eta = order.getEstimatedDeliveryAt();
			events.add(new Event(null, d.late() ? "Running late" : "Expected delivery",
					(d.late() ? "Was due " : "By ") + fmt.time(eta), Event.Kind.UPCOMING, "clock", start));
		}
		return events;
	}

	/** Why an ended order cannot be cancelled. */
	static String refusal(Order order) {
		return order.getCode() + switch (order.getStatus()) {
			case DELIVERED -> " was already delivered, so it can't be cancelled.";
			case REJECTED -> " was already rejected by the restaurant, so there is nothing to cancel.";
			case CANCELLED -> " is already cancelled.";
			default -> " can't be cancelled right now.";
		};
	}

	/**
	 * The order page's data: the order, its payment attempts (oldest first), its timeline ({@link Event}s, oldest
	 * first), whether the admin may still cancel it, and when it was read.
	 */
	public record Detail(Order order, List<Attempt> attempts, List<Event> timeline, boolean canCancel, LocalDateTime now) {

		/** The online payment that went through, or null. */
		public Attempt paidWith() {
			return attempts.stream().filter(Attempt::succeeded).reduce((first, second) -> second).orElse(null);
		}

		/** Still moving (placed and not ended). */
		public boolean live() {
			return order.getStatus() != OrderStatus.PENDING_PAYMENT && !order.getStatus().isFinal();
		}

		/** Live and past its estimated delivery time. */
		public boolean late() {
			return live() && now.isAfter(order.getEstimatedDeliveryAt());
		}

		/** Who ended a cancelled or rejected order, for the admin; null otherwise. */
		public String endedBy() {
			if (order.getStatus() == OrderStatus.REJECTED) {
				return "Rejected by " + order.getRestaurant().getName();
			}
			if (order.getStatus() != OrderStatus.CANCELLED) {
				return null;
			}
			if (order.getCancelledBy() == null) {
				return "Cancelled";
			}
			return switch (order.getCancelledBy()) {
				case CUSTOMER -> "Cancelled by the customer";
				case ADMIN -> "Cancelled by Zaika support";
				case RESTAURANT -> "Cancelled by " + order.getRestaurant().getName();
				case RIDER -> "Cancelled by the delivery partner";
				case SYSTEM -> "Cancelled automatically";
			};
		}

	}

	/**
	 * One line of the timeline. {@code at} is null for what is still to come; {@code offset} says how long after
	 * checkout it happened ("at checkout", "+12 min", "+1 h 05 min").
	 */
	public record Event(LocalDateTime at, String title, String note, Kind kind, String icon, LocalDateTime start) {

		public enum Kind {
			DONE, CURRENT, UPCOMING, FAILED
		}

		/** The stepper's class. */
		public String cssClass() {
			return switch (kind) {
				case DONE -> "is-done";
				case CURRENT -> "is-current";
				case FAILED -> "is-failed";
				case UPCOMING -> "";
			};
		}

		/** Time since checkout. */
		public String offset() {
			if (at == null) {
				return null;
			}
			long minutes = Duration.between(start, at).toMinutes();
			if (minutes < 1) {
				return "at checkout";
			}
			return minutes < 60 ? "+" + minutes + " min" : "+%d h %02d min".formatted(minutes / 60, minutes % 60);
		}

	}

}
