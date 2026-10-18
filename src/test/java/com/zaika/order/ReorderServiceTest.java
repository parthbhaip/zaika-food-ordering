package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.OrderFixtures;
import com.zaika.user.User;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" }) // same context as OrderPagesTest (cached, not a new one)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReorderServiceTest {

	@Autowired
	ReorderService reorders;

	@Autowired
	OrderFixtures orders;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuItemRepository items;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	CartService carts;

	User customer;

	Restaurant restaurant;

	MenuItem available;

	MenuItem soldOut;

	@BeforeEach
	void setUp() {
		customer = orders.customer();
		String slug = "reorder-" + java.util.UUID.randomUUID().toString().substring(0, 8);
		restaurant = fixtures.restaurant(slug, "Reorder Kitchen")
			.dishes("Available Dish", "Sold Out Dish", "Old Cart Dish").save();
		var menu = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId());
		available = menu.stream().filter(i -> i.getName().equals("Available Dish")).findFirst().orElseThrow();
		soldOut = menu.stream().filter(i -> i.getName().equals("Sold Out Dish")).findFirst().orElseThrow();
	}

	@Test
	void reorderReplacesCartAndReportsUnavailableAndChangedPrices() {
		Order order = orders.place(customer.getId(), available.getId(), 2, PaymentMethod.COD, OrderStatus.DELIVERED);
		tx.executeWithoutResult(status -> orderRepository.findTrackedByCodeAndCustomerId(order.getCode(), customer.getId())
			.orElseThrow().addItem(items.findWithRestaurantById(soldOut.getId()).orElseThrow(), 1));
		MenuItem changed = items.findById(available.getId()).orElseThrow();
		changed.setPrice(new BigDecimal("250.00"));
		items.save(changed);
		MenuItem unavailable = items.findById(soldOut.getId()).orElseThrow();
		unavailable.setAvailable(false);
		items.save(unavailable);
		MenuItem oldCart = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId()).stream()
			.filter(i -> i.getName().equals("Old Cart Dish")).findFirst().orElseThrow();
		carts.setQuantity(new CartOwner.Customer(customer.getId()), oldCart.getId(), 1, false);

		ReorderService.Reorder result = reorders.reorder(customer.getId(), order.getCode());
		var cart = carts.view(new CartOwner.Customer(customer.getId()));
		assertThat(result.dishes()).isEqualTo(1);
		assertThat(result.leftOutNotice()).contains("Sold Out Dish");
		assertThat(result.replaced()).isTrue();
		assertThat(cart.lines()).hasSize(1);
		assertThat(cart.lines().getFirst().quantity()).isEqualTo(2);
		assertThat(cart.notices()).anyMatch(n -> n.contains("now costs ₹250 (was ₹199)"));
	}

	@Test
	void openOrdersCannotBeReordered() {
		Order order = orders.create(customer.getId(), available.getId(), 1, PaymentMethod.COD);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> reorders.reorder(customer.getId(), order.getCode()))
			.isInstanceOf(ReorderException.class).hasMessageContaining("still open");
	}

}
