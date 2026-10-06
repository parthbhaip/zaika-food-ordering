package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.User;

/**
 * Orders against a real database of their own: the customer-facing code, the snapshots that later menu edits cannot
 * touch, timestamps written through the workflow, optimistic locking between two people acting at once, and the
 * schema's own checks. The test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:orders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPersistenceTest {

	@Autowired
	OrderFixtures orders;

	@Autowired
	OrderRepository repository;

	@Autowired
	OrderWorkflow workflow;

	@Autowired
	CatalogFixtures catalog;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	Restaurant kitchen;

	MenuItem biryani; // ₹280, non-veg

	@BeforeAll
	void createKitchen() {
		kitchen = catalog.restaurant("orders-kitchen", "Tambda Rassa House").save();
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
		biryani = items.save(new MenuItem(menu, "Mutton Biryani", new BigDecimal("280"), false));
	}

	@Test
	void aSavedOrderGetsItsCodeFromTheYearAndId() {
		Order order = orders.create(orders.customer().getId(), biryani.getId(), 1, PaymentMethod.ONLINE);

		assertThat(order.getCode()).isEqualTo("ZK-26-%06d".formatted(order.getId()));
		assertThat(jdbc.queryForObject("SELECT code FROM orders WHERE id = ?", String.class, order.getId()))
			.isEqualTo(order.getCode());
		assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.getId()))
			.isEqualTo("PENDING_PAYMENT");
		assertThat(jdbc.queryForObject("SELECT created_at FROM orders WHERE id = ?", java.time.LocalDateTime.class,
				order.getId())).isEqualTo(MutableClock.DEFAULT_NOW);
	}

	@Test
	void dishesAddressAndBillAreSnapshotsThatMenuEditsDoNotChange() {
		Order order = orders.create(orders.customer().getId(), biryani.getId(), 2, PaymentMethod.COD);

		tx.executeWithoutResult(s -> {
			MenuItem dish = items.findById(biryani.getId()).orElseThrow();
			dish.setName("Mutton Biryani (Full)");
			dish.setPrice(new BigDecimal("320"));
		});

		tx.executeWithoutResult(s -> {
			Order saved = repository.findById(order.getId()).orElseThrow();
			assertThat(saved.getItems()).singleElement().satisfies(line -> {
				assertThat(line.getName()).isEqualTo("Mutton Biryani");
				assertThat(line.getUnitPrice()).isEqualTo(new BigDecimal("280.00"));
				assertThat(line.getQuantity()).isEqualTo(2);
				assertThat(line.getLineTotal()).isEqualByComparingTo("560");
				assertThat(line.isVeg()).isFalse();
				assertThat(line.getMenuItem().getId()).isEqualTo(biryani.getId());
			});
			assertThat(saved.getItemCount()).isEqualTo(2);
			assertThat(saved.getAddress()).isEqualTo(OrderFixtures.ADDRESS);
			// ₹560 + ₹0 delivery (above ₹499) + ₹5 platform + 5% GST on ₹560
			assertThat(saved.getBill().itemTotal()).isEqualTo(new BigDecimal("560.00"));
			assertThat(saved.getBill().deliveryFee()).isEqualTo(new BigDecimal("0.00"));
			assertThat(saved.getBill().gst()).isEqualTo(new BigDecimal("28.00"));
			assertThat(saved.getBill().grandTotal()).isEqualTo(new BigDecimal("593.00"));
			assertThat(saved.getDeliveryOtp()).matches("[0-9]{4}");
			assertThat(saved.getEstimatedDeliveryAt()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(30));
		});

		tx.executeWithoutResult(s -> {
			MenuItem dish = items.findById(biryani.getId()).orElseThrow();
			dish.setName("Mutton Biryani");
			dish.setPrice(new BigDecimal("280"));
		});
	}

	@Test
	void aDeliveredOrderKeepsEveryTimestampAndIsPaidInCash() {
		Order order = orders.place(orders.customer().getId(), biryani.getId(), 1, PaymentMethod.COD,
				OrderStatus.DELIVERED);

		var row = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order.getId());
		assertThat(row.get("status")).isEqualTo("DELIVERED");
		assertThat(row.get("payment_status")).isEqualTo("PAID");
		for (String column : new String[] { "placed_at", "accepted_at", "preparing_at", "ready_at",
				"out_for_delivery_at", "delivered_at" }) {
			assertThat(row.get(column)).as(column).isNotNull();
		}
		assertThat(row.get("cancelled_at")).isNull();
		assertThat(row.get("rejected_at")).isNull();
		assertThat(row.get("rider_id")).isNotNull();
	}

	@Test
	void aCancelledOrderStoresItsReasonAndWhoCancelled() {
		Order order = orders.place(orders.customer().getId(), biryani.getId(), 1, PaymentMethod.ONLINE,
				OrderStatus.CANCELLED);

		var row = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order.getId());
		assertThat(row.get("status")).isEqualTo("CANCELLED");
		assertThat(row.get("cancel_reason")).isEqualTo("Ordered by mistake");
		assertThat(row.get("cancelled_by")).isEqualTo("CUSTOMER");
		assertThat(row.get("cancelled_at")).isNotNull();
	}

	@Test
	void twoPeopleActingOnOneOrderAtOnceCannotBothWin() {
		User customer = orders.customer();
		Order order = orders.place(customer.getId(), biryani.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		long ownerId = jdbc.queryForObject("SELECT owner_id FROM restaurants WHERE id = ?", Long.class, kitchen.getId());

		// both load version N; the restaurant accepts first, then the customer's cancel is refused
		Order ownersCopy = tx.execute(s -> repository.findById(order.getId()).orElseThrow());
		Order customersCopy = tx.execute(s -> repository.findById(order.getId()).orElseThrow());
		tx.executeWithoutResult(s -> {
			Order o = repository.findById(order.getId()).orElseThrow();
			workflow.transition(o, OrderStatus.ACCEPTED, OrderActor.restaurant(ownerId));
		});
		assertThat(ownersCopy.getVersion()).isEqualTo(customersCopy.getVersion());

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			Order stale = repository.findById(order.getId()).orElseThrow();
			workflow.transition(stale, OrderStatus.CANCELLED, OrderActor.customer(customer.getId()), "Too slow");
		})).as("the fresh copy shows ACCEPTED, which the customer cannot cancel").isInstanceOf(
				OrderTransitionException.class);

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			workflow.transition(customersCopy, OrderStatus.CANCELLED, OrderActor.customer(customer.getId()),
					"Too slow");
			repository.save(customersCopy);
		})).as("a stale copy loses on its version").isInstanceOf(ObjectOptimisticLockingFailureException.class);

		assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.getId()))
			.isEqualTo("ACCEPTED");
	}

	@Test
	void customersWhoOrderedAreFoundByTheFirstOrderQuery() {
		User delivered = orders.customer();
		orders.place(delivered.getId(), biryani.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		User unpaid = orders.customer();
		orders.create(unpaid.getId(), biryani.getId(), 1, PaymentMethod.ONLINE);
		User rejected = orders.customer();
		orders.place(rejected.getId(), biryani.getId(), 1, PaymentMethod.COD, OrderStatus.REJECTED);

		assertThat(repository.existsByCustomerIdAndStatusIn(delivered.getId(), OrderStatus.PLACED_AND_LIVE)).isTrue();
		assertThat(repository.existsByCustomerIdAndStatusIn(unpaid.getId(), OrderStatus.PLACED_AND_LIVE)).isFalse();
		assertThat(repository.existsByCustomerIdAndStatusIn(rejected.getId(), OrderStatus.PLACED_AND_LIVE)).isFalse();
	}

	@Test
	void theDatabaseRefusesImpossibleOrders() {
		Order order = orders.create(orders.customer().getId(), biryani.getId(), 1, PaymentMethod.COD);
		long id = order.getId();

		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'LOST' WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET payment_method = 'CHEQUE' WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET payment_status = 'MAYBE' WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET delivery_otp = '123' WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET discount = item_total + 1 WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET cancelled_by = 'NOBODY' WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET quantity = 21 WHERE order_id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		String otherCode = orders.create(orders.customer().getId(), biryani.getId(), 1, PaymentMethod.COD).getCode();
		assertThatThrownBy(() -> jdbc.update("UPDATE orders SET code = ? WHERE id = ?", otherCode, id))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
