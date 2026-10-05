package com.zaika.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.cart.CartChange.Outcome;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.NotFoundException;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Cart rules on their own database, for saved (customer) and session (guest) carts alike. Every test uses fresh
 * customers and fresh dishes, so tests never share a cart. The test clock says Sunday 19:30 IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cart;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartServiceTest {

	@Autowired
	CartService carts;

	@Autowired
	CartRepository cartRepository;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	UserRepository users;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	Restaurant rassa;

	Restaurant sunrise;

	Restaurant morningOnly;

	Restaurant paused;

	@BeforeAll
	void createRestaurants() {
		rassa = fixtures.restaurant("rassa-house", "Rassa House").save();
		sunrise = fixtures.restaurant("sunrise-misal", "Sunrise Misal").pureVeg().save();
		morningOnly = fixtures.restaurant("morning-only", "Morning Only").hours("06:00", "10:00").save();
		paused = fixtures.restaurant("paused-place", "Paused Place").paused().save();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	CartOwner.Customer customer() {
		String email = "c-" + UUID.randomUUID() + "@cart.zaika.test";
		User user = users.save(new User("Riya Shinde", email, "9876543210", "{noop}x", Role.CUSTOMER));
		return new CartOwner.Customer(user.getId());
	}

	MenuItem dish(Restaurant restaurant, String name, String price) {
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(restaurant.getId()).get(0);
		return items.save(new MenuItem(menu, name, new BigDecimal(price), restaurant.isPureVeg()));
	}

	void change(MenuItem item, Consumer<MenuItem> edit) {
		MenuItem fresh = items.findById(item.getId()).orElseThrow();
		edit.accept(fresh);
		items.save(fresh);
	}

	// ---------------------------------------------------------------- adding and quantities

	@Test
	void addingStoresTheDishWithItsPriceForTheCustomer() {
		CartOwner owner = customer();
		MenuItem thali = dish(rassa, "Mutton Thali", "420");

		CartChange change = carts.setQuantity(owner, thali.getId(), 1, false);

		assertThat(change.outcome()).isEqualTo(Outcome.ADDED);
		assertThat(change.quantity()).isEqualTo(1);
		assertThat(carts.quantities(owner)).containsExactly(entry(thali.getId(), 1));
		assertThat(carts.count(owner)).isEqualTo(1);
		CartView view = carts.view(owner);
		assertThat(view.restaurant().getId()).isEqualTo(rassa.getId());
		assertThat(view.takingOrders()).isTrue();
		assertThat(view.lines()).singleElement().satisfies(line -> {
			assertThat(line.item().getName()).isEqualTo("Mutton Thali");
			assertThat(line.lineTotal()).isEqualByComparingTo("420.00");
		});
		assertThat(view.notices()).isEmpty();
	}

	@Test
	void quantitiesGoUpAndDownAndZeroRemoves() {
		CartOwner owner = customer();
		MenuItem thali = dish(rassa, "Veg Thali", "260.50");
		MenuItem solkadhi = dish(rassa, "Solkadhi", "60");
		carts.setQuantity(owner, thali.getId(), 1, false);
		carts.setQuantity(owner, solkadhi.getId(), 2, false);

		assertThat(carts.setQuantity(owner, thali.getId(), 3, false).outcome()).isEqualTo(Outcome.UPDATED);
		assertThat(carts.setQuantity(owner, thali.getId(), 3, false).outcome()).isEqualTo(Outcome.UNCHANGED);
		CartView view = carts.view(owner);
		assertThat(view.itemCount()).isEqualTo(5);
		assertThat(view.itemTotal()).isEqualByComparingTo("901.50").satisfies(t -> assertThat(t.scale()).isEqualTo(2));

		assertThat(carts.setQuantity(owner, solkadhi.getId(), 0, false).outcome()).isEqualTo(Outcome.REMOVED);
		assertThat(carts.quantities(owner)).containsExactly(entry(thali.getId(), 3));
		assertThat(carts.setQuantity(owner, solkadhi.getId(), 0, false).outcome()).isEqualTo(Outcome.UNCHANGED);
	}

	@Test
	void quantityIsCappedAtTwentyAndNeverNegative() {
		CartOwner owner = customer();
		MenuItem thali = dish(rassa, "Chicken Thali", "380");

		CartChange change = carts.setQuantity(owner, thali.getId(), 25, false);
		assertThat(change.quantity()).isEqualTo(CartService.MAX_QUANTITY).isEqualTo(20);
		assertThat(change.capped()).isTrue();
		assertThat(carts.setQuantity(owner, thali.getId(), 21, false))
			.satisfies(c -> assertThat(c.outcome()).isEqualTo(Outcome.UNCHANGED))
			.satisfies(c -> assertThat(c.capped()).isTrue());
		assertThat(carts.quantities(owner)).containsExactly(entry(thali.getId(), 20));
		assertThat(carts.setQuantity(owner, thali.getId(), 19, false).capped()).isFalse();
		assertThatThrownBy(() -> carts.setQuantity(owner, thali.getId(), -1, false))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theDatabaseRejectsQuantitiesOutsideOneToTwenty() {
		CartOwner.Customer owner = customer();
		MenuItem thali = dish(rassa, "Prawn Thali", "520");
		carts.setQuantity(owner, thali.getId(), 1, false);
		Long cartId = cartRepository.findByUserId(owner.userId()).orElseThrow().getId();
		for (int bad : new int[] { 0, 21 }) {
			assertThatThrownBy(() -> jdbc.update("update cart_items set quantity = ? where cart_id = ?", bad, cartId))
				.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void soldOutDishesAndClosedOrPausedKitchensCannotBeAdded() {
		CartOwner owner = customer();
		MenuItem gone = dish(rassa, "Kheema Pav", "180");
		change(gone, item -> item.setAvailable(false));

		assertThat(carts.setQuantity(owner, gone.getId(), 1, false).outcome()).isEqualTo(Outcome.SOLD_OUT);
		assertThat(carts.setQuantity(owner, dish(morningOnly, "Poha", "60").getId(), 1, false).outcome())
			.isEqualTo(Outcome.CLOSED);
		assertThat(carts.setQuantity(owner, dish(paused, "Margherita", "250").getId(), 1, false).outcome())
			.isEqualTo(Outcome.CLOSED);
		assertThat(carts.quantities(owner)).isEmpty();
	}

	@Test
	void lowerQuantitiesStillWorkOnceTheKitchenCloses() {
		CartOwner owner = customer();
		MenuItem upma = dish(morningOnly, "Upma", "70");
		clock.setTo(MutableClock.DEFAULT_NOW.with(LocalTime.of(8, 0)));
		carts.setQuantity(owner, upma.getId(), 3, false);
		clock.reset(); // 19:30: closed

		assertThat(carts.setQuantity(owner, upma.getId(), 4, false).outcome()).isEqualTo(Outcome.CLOSED);
		assertThat(carts.setQuantity(owner, upma.getId(), 2, false).outcome()).isEqualTo(Outcome.UPDATED);
		CartView view = carts.view(owner);
		assertThat(view.takingOrders()).isFalse();
		assertThat(view.opensAt()).isEqualTo(LocalTime.of(6, 0));
		assertThat(view.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
	}

	@Test
	void archivedUnknownAndUnapprovedDishesAreNotFound() {
		CartOwner owner = customer();
		MenuItem archived = dish(rassa, "Old Pasta", "250");
		change(archived, item -> item.setArchived(true));
		Restaurant pending = fixtures.restaurant("pending-cart", "Pending Kitchen").status(RestaurantStatus.PENDING)
			.dishes("Vada Pav").save();
		MenuItem pendingDish = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(pending.getId()).get(0);

		for (long id : new long[] { archived.getId(), pendingDish.getId(), Long.MAX_VALUE }) {
			assertThatThrownBy(() -> carts.setQuantity(owner, id, 1, false)).isInstanceOf(NotFoundException.class);
		}
	}

	// ---------------------------------------------------------------- one restaurant per cart

	@Test
	void aDishFromAnotherKitchenNeedsConfirmationThenStartsANewCart() {
		CartOwner owner = customer();
		MenuItem thali = dish(rassa, "Fish Thali", "450");
		MenuItem misal = dish(sunrise, "Misal Pav", "90");
		carts.setQuantity(owner, thali.getId(), 2, false);

		assertThat(carts.replacePrompt(owner, thali.getId())).isEmpty();
		assertThat(carts.replacePrompt(owner, misal.getId())).hasValueSatisfying(prompt -> {
			assertThat(prompt.currentRestaurant()).isEqualTo("Rassa House");
			assertThat(prompt.item().getName()).isEqualTo("Misal Pav");
		});
		CartChange asked = carts.setQuantity(owner, misal.getId(), 1, false);
		assertThat(asked.outcome()).isEqualTo(Outcome.OTHER_RESTAURANT);
		assertThat(carts.quantities(owner)).containsExactly(entry(thali.getId(), 2));

		assertThat(carts.setQuantity(owner, misal.getId(), 1, true).outcome()).isEqualTo(Outcome.ADDED);
		assertThat(carts.quantities(owner)).containsExactly(entry(misal.getId(), 1));
		assertThat(carts.view(owner).restaurant().getName()).isEqualTo("Sunrise Misal");
	}

	@Test
	void guestCartsFollowTheSameRules() {
		GuestCart guestCart = new GuestCart();
		CartOwner guest = new CartOwner.Guest(guestCart);
		MenuItem thali = dish(rassa, "Egg Thali", "220");
		MenuItem misal = dish(sunrise, "Tarri Misal", "110");

		assertThat(carts.setQuantity(guest, thali.getId(), 30, false).quantity()).isEqualTo(20);
		assertThat(carts.setQuantity(guest, misal.getId(), 1, false).outcome()).isEqualTo(Outcome.OTHER_RESTAURANT);
		assertThat(carts.count(guest)).isEqualTo(20);
		assertThat(carts.setQuantity(guest, misal.getId(), 1, true).outcome()).isEqualTo(Outcome.ADDED);
		assertThat(guestCart.restaurantId()).isEqualTo(sunrise.getId());
		assertThat(carts.setQuantity(guest, misal.getId(), 0, false).outcome()).isEqualTo(Outcome.REMOVED);
		assertThat(guestCart.isEmpty()).isTrue();
		assertThat(guestCart.restaurantId()).isNull();
	}

	@Test
	void customersOnlyEverSeeTheirOwnCart() {
		CartOwner riya = customer();
		CartOwner aarav = customer();
		carts.setQuantity(riya, dish(rassa, "Sukka Mutton", "340").getId(), 2, false);

		assertThat(carts.view(aarav).isEmpty()).isTrue();
		assertThat(carts.count(aarav)).isZero();
		assertThat(carts.count(riya)).isEqualTo(2);
	}

	// ---------------------------------------------------------------- every view re-checks the menu

	@Test
	void aViewPicksUpNewPricesAndSaysSoOnce() {
		CartOwner owner = customer();
		MenuItem thali = dish(rassa, "Tambda Rassa Thali", "420");
		carts.setQuantity(owner, thali.getId(), 2, false);
		change(thali, item -> item.setPrice(new BigDecimal("440")));

		CartView view = carts.view(owner);
		assertThat(view.notices()).containsExactly("Tambda Rassa Thali now costs ₹440 (was ₹420).");
		assertThat(view.itemTotal()).isEqualByComparingTo("880.00");
		assertThat(carts.view(owner).notices()).isEmpty();
	}

	@Test
	void aViewTakesOutDishesThatSoldOutOrLeftTheMenu() {
		CartOwner owner = customer();
		MenuItem soldOut = dish(rassa, "Bombil Fry", "260");
		MenuItem archived = dish(rassa, "Paya Soup", "150");
		MenuItem kept = dish(rassa, "Bhakri", "25");
		for (MenuItem item : new MenuItem[] { soldOut, archived, kept }) {
			carts.setQuantity(owner, item.getId(), 1, false);
		}
		change(soldOut, item -> item.setAvailable(false));
		change(archived, item -> item.setArchived(true));

		CartView view = carts.view(owner);
		assertThat(view.notices()).containsExactly("Bombil Fry just sold out, so we removed it from your cart.",
				"Paya Soup was taken off the menu, so we removed it from your cart.");
		assertThat(view.lines()).extracting(line -> line.item().getName()).containsExactly("Bhakri");
		assertThat(carts.quantities(owner)).containsOnlyKeys(kept.getId());
	}

	@Test
	void aViewEmptiesTheCartWhenTheKitchenIsNoLongerApproved() {
		CartOwner owner = customer();
		Restaurant leaving = fixtures.restaurant("leaving-soon", "Leaving Soon").save();
		carts.setQuantity(owner, dish(leaving, "Pav Bhaji", "140").getId(), 1, false);
		Restaurant fresh = restaurants.findById(leaving.getId()).orElseThrow();
		fresh.changeStatus(RestaurantStatus.SUSPENDED, "Hygiene check");
		restaurants.save(fresh);

		CartView view = carts.view(owner);
		assertThat(view.isEmpty()).isTrue();
		assertThat(view.notices())
			.containsExactly("Leaving Soon is not taking orders on Zaika right now, so we emptied your cart.");
		assertThat(carts.count(owner)).isZero();
	}

	// ---------------------------------------------------------------- guest cart merged on sign-in

	@Test
	void mergingTheSameKitchenAddsQuantitiesUpToTheCap() {
		CartOwner.Customer owner = customer();
		MenuItem thali = dish(rassa, "Kombdi Vade", "300");
		MenuItem bhakri = dish(rassa, "Jowar Bhakri", "30");
		carts.setQuantity(owner, thali.getId(), 15, false);
		GuestCart guest = new GuestCart();
		carts.setQuantity(new CartOwner.Guest(guest), thali.getId(), 9, false);
		carts.setQuantity(new CartOwner.Guest(guest), bhakri.getId(), 2, false);

		assertThat(carts.merge(guest, owner.userId())).isNull();
		assertThat(carts.quantities(owner)).containsExactly(entry(thali.getId(), 20), entry(bhakri.getId(), 2));
	}

	@Test
	void mergingAnotherKitchenKeepsTheGuestPicksAndSaysWhatWasCleared() {
		CartOwner.Customer owner = customer();
		MenuItem thali = dish(rassa, "Mutton Sukka", "360");
		MenuItem misal = dish(sunrise, "Puneri Misal", "95");
		carts.setQuantity(owner, thali.getId(), 1, false);
		GuestCart guest = new GuestCart();
		carts.setQuantity(new CartOwner.Guest(guest), misal.getId(), 2, false);

		assertThat(carts.merge(guest, owner.userId()))
			.isEqualTo("Your cart now has the dishes you just picked. We cleared your earlier cart from Rassa House.");
		assertThat(carts.quantities(owner)).containsExactly(entry(misal.getId(), 2));
	}

	@Test
	void mergingIntoACustomerWithoutACartCreatesOneAndKeepsPriceNotices() {
		CartOwner.Customer owner = customer();
		MenuItem misal = dish(sunrise, "Dahi Misal", "100");
		GuestCart guest = new GuestCart();
		carts.setQuantity(new CartOwner.Guest(guest), misal.getId(), 1, false);
		change(misal, item -> item.setPrice(new BigDecimal("110")));

		assertThat(carts.merge(guest, owner.userId())).isNull();
		assertThat(carts.view(owner).notices()).containsExactly("Dahi Misal now costs ₹110 (was ₹100).");
		assertThat(carts.merge(new GuestCart(), owner.userId())).isNull();
		assertThat(carts.merge(null, owner.userId())).isNull();
		assertThat(carts.quantities(owner)).containsExactly(entry(misal.getId(), 1));
	}

	@Test
	void clearEmptiesTheCart() {
		CartOwner owner = customer();
		carts.setQuantity(owner, dish(rassa, "Anda Curry", "180").getId(), 2, false);
		carts.clear(owner);
		assertThat(carts.view(owner).isEmpty()).isTrue();
	}

}
