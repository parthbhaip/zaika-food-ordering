package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.zaika.cart.PriceBreakdown;
import com.zaika.catalog.Restaurant;
import com.zaika.common.ForbiddenException;
import com.zaika.order.OrderActor.Kind;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;

/**
 * Every (from, to, who) combination of the order workflow: 9 statuses x 9 targets x 5 kinds of actor, each either
 * allowed (the order moves and gets its timestamp) or refused (the order is untouched). Plus parties, reasons and
 * payment side effects. Pure unit test: no Spring, no database.
 */
class OrderWorkflowTest {

	static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 19, 30);

	static final long CUSTOMER = 1, OWNER = 2, RIDER = 3, ADMIN = 4, STRANGER = 99;

	/** The allowed moves, written out independently of OrderWorkflow's own table: "FROM>TO" -> who. */
	static final List<Object[]> ALLOWED = List.of(
			move(OrderStatus.PENDING_PAYMENT, OrderStatus.PLACED, Kind.SYSTEM),
			move(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, Kind.CUSTOMER, Kind.ADMIN, Kind.SYSTEM),
			move(OrderStatus.PLACED, OrderStatus.ACCEPTED, Kind.RESTAURANT),
			move(OrderStatus.PLACED, OrderStatus.REJECTED, Kind.RESTAURANT),
			move(OrderStatus.PLACED, OrderStatus.CANCELLED, Kind.CUSTOMER, Kind.ADMIN),
			move(OrderStatus.ACCEPTED, OrderStatus.PREPARING, Kind.RESTAURANT),
			move(OrderStatus.ACCEPTED, OrderStatus.CANCELLED, Kind.ADMIN),
			move(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, Kind.RESTAURANT),
			move(OrderStatus.PREPARING, OrderStatus.CANCELLED, Kind.ADMIN),
			move(OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY, Kind.RIDER),
			move(OrderStatus.READY_FOR_PICKUP, OrderStatus.CANCELLED, Kind.ADMIN),
			move(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, Kind.RIDER),
			move(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED, Kind.ADMIN));

	final OrderWorkflow workflow = new OrderWorkflow(Clock.fixed(NOW.atZone(IST).toInstant(), IST));

	static Object[] move(OrderStatus from, OrderStatus to, Kind... kinds) {
		return new Object[] { from, to, Set.of(kinds) };
	}

	@SuppressWarnings("unchecked")
	static boolean allowed(OrderStatus from, OrderStatus to, Kind kind) {
		return ALLOWED.stream().anyMatch(m -> m[0] == from && m[1] == to && ((Set<Kind>) m[2]).contains(kind));
	}

	/** Whether this kind of actor makes a move to {@code to} from any status at all. */
	@SuppressWarnings("unchecked")
	static boolean everMovesTo(OrderStatus to, Kind kind) {
		return ALLOWED.stream().anyMatch(m -> m[1] == to && ((Set<Kind>) m[2]).contains(kind));
	}

	static Stream<Arguments> everyCombination() {
		List<Arguments> all = new ArrayList<>();
		for (OrderStatus from : OrderStatus.values()) {
			for (OrderStatus to : OrderStatus.values()) {
				for (Kind kind : Kind.values()) {
					all.add(Arguments.of(from, to, kind));
				}
			}
		}
		return all.stream();
	}

	static User user(long id, Role role) {
		User user = new User("User " + id, "u" + id + "@workflow.zaika.test", "9876543210", "hash", role);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	static PriceBreakdown bill() {
		return new PriceBreakdown(new BigDecimal("250.00"), new BigDecimal("0.00"), new BigDecimal("30.00"),
				new BigDecimal("5.00"), new BigDecimal("12.50"), new BigDecimal("297.50"));
	}

	/** An order from customer 1 at owner 2's restaurant, assigned to rider 3, sitting at {@code status}. */
	static Order orderAt(OrderStatus status, PaymentMethod method) {
		Restaurant restaurant = new Restaurant(user(OWNER, Role.RESTAURANT_OWNER), "workflow-kitchen", "Workflow Kitchen");
		Order order = new Order(user(CUSTOMER, Role.CUSTOMER), restaurant, OrderFixtures.ADDRESS, method, bill(), null,
				NOW.plusMinutes(30));
		ReflectionTestUtils.setField(order, "code", "ZK-26-000001");
		order.assignRider(user(RIDER, Role.DELIVERY_PARTNER));
		if (status != OrderStatus.PENDING_PAYMENT) {
			order.moveTo(status, NOW.minusHours(1));
		}
		return order;
	}

	static Order orderAt(OrderStatus status) {
		return orderAt(status, PaymentMethod.COD);
	}

	static OrderActor actor(Kind kind) {
		return switch (kind) {
			case CUSTOMER -> OrderActor.customer(CUSTOMER);
			case RESTAURANT -> OrderActor.restaurant(OWNER);
			case RIDER -> OrderActor.rider(RIDER);
			case ADMIN -> OrderActor.admin(ADMIN);
			case SYSTEM -> OrderActor.system();
		};
	}

	@ParameterizedTest(name = "{0} -> {1} by {2}")
	@MethodSource("everyCombination")
	void everyMoveIsAllowedOnlyForTheRolesThatMakeIt(OrderStatus from, OrderStatus to, Kind kind) {
		Order order = orderAt(from);
		OrderActor actor = actor(kind);

		if (allowed(from, to, kind)) {
			assertThat(workflow.can(order, to, actor)).isTrue();
			workflow.transition(order, to, actor, "Customer asked");
			assertThat(order.getStatus()).isEqualTo(to);
			assertThat(order.timestampOf(to)).isEqualTo(NOW);
		}
		else {
			assertThat(workflow.can(order, to, actor)).isFalse();
			// a role that never makes this move is forbidden; one that does, but not from here, hit a stale order
			Class<? extends RuntimeException> expected = everMovesTo(to, kind) ? OrderTransitionException.class
					: ForbiddenException.class;
			assertThatThrownBy(() -> workflow.transition(order, to, actor, "Customer asked")).isInstanceOf(expected);
			assertThat(order.getStatus()).isEqualTo(from);
			assertThat(order.getCancelReason()).isNull();
			assertThat(order.getCancelledBy()).isNull();
		}
	}

	@Test
	void theTableCoversThirteenMovesAndFinalStatusesHaveNone() {
		long allowedCount = everyCombination().filter(a -> {
			Object[] g = a.get();
			return allowed((OrderStatus) g[0], (OrderStatus) g[1], (Kind) g[2]);
		}).count();
		assertThat(allowedCount).isEqualTo(16); // 13 moves; cancelling an unpaid order is open to 3 roles, a placed one to 2

		for (OrderStatus done : EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.REJECTED)) {
			assertThat(done.isFinal()).isTrue();
			for (OrderStatus to : OrderStatus.values()) {
				assertThat(workflow.can(orderAt(done), to, OrderActor.admin(ADMIN))).isFalse();
			}
		}
	}

	@Test
	void aHappyCashOrderWalksTheWholeWayAndEveryStepIsStamped() {
		Order order = orderAt(OrderStatus.PENDING_PAYMENT);
		OrderStatus[] path = { OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
				OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED };
		Kind[] by = { Kind.SYSTEM, Kind.RESTAURANT, Kind.RESTAURANT, Kind.RESTAURANT, Kind.RIDER, Kind.RIDER };
		for (int i = 0; i < path.length; i++) {
			LocalDateTime at = NOW.plusMinutes(5L * i);
			new OrderWorkflow(Clock.fixed(at.atZone(IST).toInstant(), IST)).transition(order, path[i], actor(by[i]));
			assertThat(order.getStatus()).isEqualTo(path[i]);
			assertThat(order.timestampOf(path[i])).isEqualTo(at);
		}
		assertThat(order.timestampOf(OrderStatus.PLACED)).isEqualTo(NOW);
		assertThat(order.timestampOf(OrderStatus.DELIVERED)).isEqualTo(NOW.plusMinutes(25));
		assertThat(order.timestampOf(OrderStatus.CANCELLED)).isNull();
		assertThat(order.getPaymentStatus()).as("cash is collected at the door").isEqualTo(PaymentStatus.PAID);
	}

	@Test
	void anotherCustomerOwnerOrRiderIsNotAPartyToTheOrder() {
		assertThatThrownBy(() -> workflow.transition(orderAt(OrderStatus.PLACED), OrderStatus.CANCELLED,
				OrderActor.customer(STRANGER), "Mine now")).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> workflow.transition(orderAt(OrderStatus.PLACED), OrderStatus.ACCEPTED,
				OrderActor.restaurant(STRANGER))).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> workflow.transition(orderAt(OrderStatus.READY_FOR_PICKUP),
				OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(STRANGER))).isInstanceOf(ForbiddenException.class);
		assertThat(workflow.can(orderAt(OrderStatus.PLACED), OrderStatus.ACCEPTED, OrderActor.restaurant(STRANGER)))
			.isFalse();
	}

	@Test
	void anOrderNobodyClaimedCannotBePickedUpByAnyRider() {
		Restaurant restaurant = new Restaurant(user(OWNER, Role.RESTAURANT_OWNER), "unclaimed", "Unclaimed Kitchen");
		Order order = new Order(user(CUSTOMER, Role.CUSTOMER), restaurant, OrderFixtures.ADDRESS, PaymentMethod.COD,
				bill(), null, NOW);
		order.moveTo(OrderStatus.READY_FOR_PICKUP, NOW);
		assertThatThrownBy(() -> workflow.transition(order, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(RIDER)))
			.isInstanceOf(ForbiddenException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
	}

	@ParameterizedTest
	@EnumSource(value = OrderStatus.class, names = { "CANCELLED", "REJECTED" })
	void cancellingOrRejectingNeedsAReasonAndRecordsWho(OrderStatus target) {
		OrderActor actor = target == OrderStatus.CANCELLED ? OrderActor.customer(CUSTOMER) : OrderActor.restaurant(OWNER);
		for (String missing : new String[] { null, "", "   " }) {
			Order order = orderAt(OrderStatus.PLACED);
			assertThatThrownBy(() -> workflow.transition(order, target, actor, missing))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> workflow.transition(order, target, actor))
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
		}
		Order tooLong = orderAt(OrderStatus.PLACED);
		assertThatThrownBy(() -> workflow.transition(tooLong, target, actor, "x".repeat(Order.MAX_REASON + 1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(tooLong.getStatus()).isEqualTo(OrderStatus.PLACED);

		Order order = orderAt(OrderStatus.PLACED);
		workflow.transition(order, target, actor, "  Out of paneer today  ");
		assertThat(order.getStatus()).isEqualTo(target);
		assertThat(order.getCancelReason()).isEqualTo("Out of paneer today");
		assertThat(order.getCancelledBy()).isEqualTo(actor.kind());
		assertThat(order.timestampOf(target)).isEqualTo(NOW);
	}

	@Test
	void aReasonOnAnOrdinaryMoveIsIgnored() {
		Order order = orderAt(OrderStatus.PLACED);
		workflow.transition(order, OrderStatus.ACCEPTED, OrderActor.restaurant(OWNER), "Ignored");
		assertThat(order.getCancelReason()).isNull();
		assertThat(order.getCancelledBy()).isNull();
	}

	@Test
	void aPaidOrderThatIsCancelledOrRejectedAwaitsItsRefund() {
		Order rejected = orderAt(OrderStatus.PLACED, PaymentMethod.ONLINE);
		rejected.setPaymentStatus(PaymentStatus.PAID);
		workflow.transition(rejected, OrderStatus.REJECTED, OrderActor.restaurant(OWNER), "Kitchen closing early");
		assertThat(rejected.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);

		Order cancelled = orderAt(OrderStatus.OUT_FOR_DELIVERY, PaymentMethod.ONLINE);
		cancelled.setPaymentStatus(PaymentStatus.PAID);
		workflow.transition(cancelled, OrderStatus.CANCELLED, OrderActor.admin(ADMIN), "Rider had an accident");
		assertThat(cancelled.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
		assertThat(cancelled.getCancelledBy()).isEqualTo(Kind.ADMIN);
	}

	@Test
	void anUnpaidOrCashOrderThatEndsEarlyOwesNothing() {
		Order unpaid = orderAt(OrderStatus.PENDING_PAYMENT, PaymentMethod.ONLINE);
		workflow.transition(unpaid, OrderStatus.CANCELLED, OrderActor.system(), "Payment not completed in 15 minutes");
		assertThat(unpaid.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(unpaid.getCancelledBy()).isEqualTo(Kind.SYSTEM);

		Order cash = orderAt(OrderStatus.PLACED, PaymentMethod.COD);
		workflow.transition(cash, OrderStatus.CANCELLED, OrderActor.customer(CUSTOMER), "Ordered by mistake");
		assertThat(cash.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	void anOnlineOrderStaysAsPaidWhenDelivered() {
		Order order = orderAt(OrderStatus.OUT_FOR_DELIVERY, PaymentMethod.ONLINE);
		order.setPaymentStatus(PaymentStatus.PAID);
		workflow.transition(order, OrderStatus.DELIVERED, OrderActor.rider(RIDER));
		assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
	}

	@Test
	void aConfirmedOnlinePaymentPlacesTheOrderAsPaid() {
		Order order = orderAt(OrderStatus.PENDING_PAYMENT, PaymentMethod.ONLINE);
		order.paymentFailed(); // an earlier attempt was declined
		assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);

		workflow.confirmPayment(order);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
		assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(order.timestampOf(OrderStatus.PLACED)).isEqualTo(NOW);
		// paid twice, or a cash order "paid online": refused, nothing changes
		assertThatThrownBy(() -> workflow.confirmPayment(order)).isInstanceOf(OrderTransitionException.class);
		Order cash = orderAt(OrderStatus.PENDING_PAYMENT, PaymentMethod.COD);
		assertThatThrownBy(() -> workflow.confirmPayment(cash)).isInstanceOf(IllegalStateException.class);
		assertThat(cash.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
	}

	@Test
	void onlyAnOrderAwaitingAnOnlinePaymentCanFailOrSwitchToCash() {
		Order order = orderAt(OrderStatus.PENDING_PAYMENT, PaymentMethod.ONLINE);
		order.paymentFailed();
		order.switchToCashOnDelivery();
		assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.COD);
		assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT); // the caller places it

		assertThatThrownBy(order::switchToCashOnDelivery).isInstanceOf(IllegalStateException.class); // now cash
		Order placed = orderAt(OrderStatus.PLACED, PaymentMethod.ONLINE);
		assertThatThrownBy(placed::paymentFailed).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(placed::switchToCashOnDelivery).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aNewOrderAwaitsPaymentWithAFourDigitOtpAndItsSnapshots() {
		Order order = orderAt(OrderStatus.PENDING_PAYMENT);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
		assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(order.getDeliveryOtp()).matches("[0-9]{4}");
		assertThat(order.getBill()).isEqualTo(bill());
		assertThat(order.getAddress().oneLine())
			.isEqualTo("Flat 4B, Shree Apartments, Rajarampuri 5th Lane, near Ganesh Mandir, Kolhapur 416008");
		for (OrderStatus status : OrderStatus.values()) {
			if (status != OrderStatus.PENDING_PAYMENT) {
				assertThat(order.timestampOf(status)).isNull();
			}
		}
	}

	@Test
	void onlyCustomersOrderAndOnlyRidersDeliver() {
		Restaurant restaurant = new Restaurant(user(OWNER, Role.RESTAURANT_OWNER), "roles", "Roles Kitchen");
		assertThatThrownBy(() -> new Order(user(5, Role.ADMIN), restaurant, OrderFixtures.ADDRESS, PaymentMethod.COD,
				bill(), null, NOW)).isInstanceOf(IllegalArgumentException.class);
		Order order = orderAt(OrderStatus.READY_FOR_PICKUP);
		assertThatThrownBy(() -> order.assignRider(user(6, Role.CUSTOMER))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void actorsMatchTheirAccountsRole() {
		assertThat(OrderActor.of(7, Role.CUSTOMER)).isEqualTo(OrderActor.customer(7));
		assertThat(OrderActor.of(7, Role.RESTAURANT_OWNER)).isEqualTo(OrderActor.restaurant(7));
		assertThat(OrderActor.of(7, Role.DELIVERY_PARTNER)).isEqualTo(OrderActor.rider(7));
		assertThat(OrderActor.of(7, Role.ADMIN)).isEqualTo(OrderActor.admin(7));
		assertThatThrownBy(() -> new OrderActor(Kind.SYSTEM, 7L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OrderActor(Kind.CUSTOMER, null)).isInstanceOf(IllegalArgumentException.class);
	}

}
