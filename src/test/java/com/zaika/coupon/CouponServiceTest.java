package com.zaika.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.cart.GuestCart;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.coupon.CouponService.CartCoupon;
import com.zaika.coupon.CouponService.CouponOffer;
import com.zaika.coupon.CouponService.CouponResult;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Coupons against real carts on their own database: applying (one test per reason a code fails, each with its
 * message), the re-check on every look at the cart, removing, the offers list, and recording uses at checkout, which
 * the limits count. Fresh customers and coupon codes per test. The test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:coupons;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponServiceTest {

	static final LocalDateTime NOW = MutableClock.DEFAULT_NOW;

	static final AtomicInteger CODES = new AtomicInteger();

	@Autowired
	CouponService service;

	@Autowired
	CouponRepository coupons;

	@Autowired
	CartService carts;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	Restaurant rassa;

	Restaurant sunrise;

	MenuItem thali; // ₹250 at Rassa

	MenuItem chai; // ₹40 at Rassa

	MenuItem misal; // ₹120 at Sunrise

	@BeforeAll
	void createRestaurants() {
		rassa = fixtures.restaurant("coupon-rassa", "Rassa Corner").save();
		sunrise = fixtures.restaurant("coupon-sunrise", "Sunrise Misal").save();
		thali = dish(rassa, "Mutton Thali", "250");
		chai = dish(rassa, "Cutting Chai", "40");
		misal = dish(sunrise, "Misal Pav", "120");
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	MenuItem dish(Restaurant restaurant, String name, String price) {
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(restaurant.getId()).get(0);
		return items.save(new MenuItem(menu, name, new BigDecimal(price), false));
	}

	long customer() {
		return users.save(new User("Riya Shinde", "c-" + UUID.randomUUID() + "@coupon.zaika.test", "9876543210",
				"hash", Role.CUSTOMER)).getId();
	}

	/** A customer whose cart holds {@code quantity} of {@code item}. */
	long customerWith(MenuItem item, int quantity) {
		long id = customer();
		carts.setQuantity(new CartOwner.Customer(id), item.getId(), quantity, false);
		return id;
	}

	/** A saved coupon with a fresh code, started yesterday, no end, no limits, after {@code setup}. */
	Coupon coupon(CouponType type, String value, Consumer<Coupon> setup) {
		Coupon coupon = new Coupon("TEST" + CODES.incrementAndGet(), "A test offer.", type, new BigDecimal(value),
				NOW.minusDays(1));
		setup.accept(coupon);
		return coupons.save(coupon);
	}

	Coupon flat(String value) {
		return coupon(CouponType.FLAT, value, c -> {
		});
	}

	Long appliedCouponId(long userId) {
		return jdbc.queryForObject("SELECT coupon_id FROM carts WHERE user_id = ?", Long.class, userId);
	}

	void assertRejected(CouponResult result, CouponProblem problem, String message) {
		assertThat(result.applied()).isFalse();
		assertThat(result.problem()).isEqualTo(problem);
		assertThat(result.message()).isEqualTo(message);
		assertThat(result.discount()).isEqualByComparingTo("0");
	}

	@Test
	void aWorkingCodeIsSavedOnTheCartAndTakesItsDiscountOffTheBill() {
		Coupon coupon = coupon(CouponType.PERCENT, "20", c -> c.setMaxDiscount(new BigDecimal("120")));
		long me = customerWith(thali, 2); // ₹500

		CouponResult result = service.apply(me, " " + coupon.getCode().toLowerCase() + " ");

		assertThat(result.applied()).isTrue();
		assertThat(result.discount()).isEqualTo(new BigDecimal("100.00"));
		assertThat(result.message()).isEqualTo(coupon.getCode() + " applied. You save ₹100.00 on this order.");
		assertThat(appliedCouponId(me)).isEqualTo(coupon.getId());

		CartCoupon box = service.forCart(new CartOwner.Customer(me), carts.view(new CartOwner.Customer(me)));
		assertThat(box.applied().code()).isEqualTo(coupon.getCode());
		assertThat(box.applied().headline()).isEqualTo("20% off up to ₹120");
		assertThat(box.discount()).isEqualTo(new BigDecimal("100.00"));
		assertThat(box.notice()).isNull();
		assertThat(box.signedIn()).isTrue();
	}

	@Test
	void aBlankCodeAsksForOne() {
		assertRejected(service.apply(customerWith(thali, 1), "   "), CouponProblem.BLANK, "Enter a coupon code.");
	}

	@Test
	void anEmptyCartHasNothingToApplyItTo() {
		Coupon coupon = flat("50");
		assertRejected(service.apply(customer(), coupon.getCode()), CouponProblem.EMPTY_CART,
				"Add a dish to your cart before applying a coupon.");
	}

	@Test
	void anUnknownCodeSaysSo() {
		long me = customerWith(thali, 1);
		assertRejected(service.apply(me, "nosuch1"), CouponProblem.UNKNOWN,
				"We could not find a coupon called NOSUCH1. Check the code and try again.");
		assertRejected(service.apply(me, "X".repeat(40)), CouponProblem.UNKNOWN,
				"We could not find a coupon called " + "X".repeat(20) + "…. Check the code and try again.");
		assertThat(appliedCouponId(me)).isNull();
	}

	@Test
	void aSwitchedOffCouponIsNotAvailable() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setActive(false));
		assertRejected(service.apply(customerWith(thali, 1), coupon.getCode()), CouponProblem.INACTIVE,
				coupon.getCode() + " is not available any more. Try another offer.");
	}

	@Test
	void aCouponThatHasNotStartedSaysWhen() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setValidity(NOW.plusDays(1).withHour(9).withMinute(0), null));
		assertRejected(service.apply(customerWith(thali, 1), coupon.getCode()), CouponProblem.NOT_STARTED,
				coupon.getCode() + " starts on 5 Oct 2026, 9:00 AM.");
	}

	@Test
	void anExpiredCouponSaysWhenItEnded() {
		// valid until midnight starting 1 Oct, so the last day it worked was 30 Sep
		Coupon coupon = coupon(CouponType.FLAT, "50",
				c -> c.setValidity(NOW.minusMonths(1), LocalDateTime.of(2026, 10, 1, 0, 0)));
		assertRejected(service.apply(customerWith(thali, 1), coupon.getCode()), CouponProblem.EXPIRED,
				coupon.getCode() + " expired on 30 Sep 2026.");
	}

	@Test
	void anotherRestaurantsCouponNamesThatRestaurant() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setRestaurant(sunrise));
		assertRejected(service.apply(customerWith(thali, 1), coupon.getCode()), CouponProblem.OTHER_RESTAURANT,
				coupon.getCode() + " works only on orders from Sunrise Misal.");
		assertThat(service.apply(customerWith(misal, 1), coupon.getCode()).applied()).isTrue();
	}

	@Test
	void belowTheMinimumOrderItSaysHowMuchMore() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setMinOrder(new BigDecimal("300")));
		long me = customerWith(thali, 1); // ₹250
		assertRejected(service.apply(me, coupon.getCode()), CouponProblem.MIN_ORDER,
				"Add ₹50 more to use " + coupon.getCode() + ". It needs a minimum order of ₹300.");

		carts.setQuantity(new CartOwner.Customer(me), chai.getId(), 2, false); // ₹330
		assertThat(service.apply(me, coupon.getCode()).applied()).isTrue();
	}

	@Test
	void aFirstOrderCouponWorksForACustomerWhoHasNotOrdered() {
		// No orders exist before T23, so every customer is on their first order; the "ordered before" refusal is
		// covered in CouponTest.aFirstOrderCouponIsOnlyForCustomersWhoNeverOrdered.
		Coupon coupon = coupon(CouponType.PERCENT, "50", c -> c.setFirstOrderOnly(true));
		assertThat(service.apply(customerWith(thali, 1), coupon.getCode()).applied()).isTrue();
	}

	@Test
	void thePerCustomerLimitCountsThatCustomersOrders() {
		Coupon once = coupon(CouponType.FLAT, "50", c -> c.setPerUserLimit(1));
		long me = customerWith(thali, 1);
		assertThat(service.apply(me, once.getCode()).applied()).isTrue();
		assertThat(service.redeem(me, carts.view(new CartOwner.Customer(me)))).get()
			.extracting(CouponResult::applied).isEqualTo(true);

		assertRejected(service.apply(me, once.getCode()), CouponProblem.USED_UP,
				"You have already used " + once.getCode() + ".");
		assertThat(service.apply(customerWith(thali, 1), once.getCode()).applied()).as("someone else").isTrue();

		Coupon thrice = coupon(CouponType.FLAT, "50", c -> c.setPerUserLimit(3));
		for (int i = 0; i < 3; i++) {
			assertThat(service.apply(me, thrice.getCode()).applied()).isTrue();
			service.redeem(me, carts.view(new CartOwner.Customer(me)));
		}
		assertRejected(service.apply(me, thrice.getCode()), CouponProblem.USED_UP,
				"You have used " + thrice.getCode() + " 3 times, the most it allows.");
	}

	@Test
	void theTotalLimitCountsEveryonesOrders() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setTotalLimit(2));
		for (int i = 0; i < 2; i++) {
			long someone = customerWith(thali, 1);
			service.apply(someone, coupon.getCode());
			service.redeem(someone, carts.view(new CartOwner.Customer(someone)));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isEqualTo(2);
		assertRejected(service.apply(customerWith(thali, 1), coupon.getCode()), CouponProblem.FULLY_CLAIMED,
				coupon.getCode() + " has been fully claimed. Try another offer.");
		assertThat(service.offers(rassa.getId())).extracting(CouponOffer::code).doesNotContain(coupon.getCode());
	}

	@Test
	void redeemingRechecksAndRecordsNothingForACouponThatStoppedWorking() {
		Coupon coupon = flat("50");
		long me = customerWith(thali, 1);
		service.apply(me, coupon.getCode());
		coupon.setActive(false);
		coupons.save(coupon);

		CouponResult result = service.redeem(me, carts.view(new CartOwner.Customer(me))).orElseThrow();

		assertThat(result.problem()).isEqualTo(CouponProblem.INACTIVE);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class,
				coupon.getId())).isZero();
		assertThat(service.redeem(customerWith(thali, 1), carts.view(new CartOwner.Customer(me)))).as("no coupon")
			.isEmpty();
	}

	@Test
	void aCodeThatFailsLeavesTheAppliedCouponInPlace() {
		Coupon good = flat("30");
		long me = customerWith(thali, 1);
		service.apply(me, good.getCode());
		service.apply(me, "NOSUCH2");
		assertThat(appliedCouponId(me)).isEqualTo(good.getId());

		Coupon better = flat("60");
		service.apply(me, better.getCode());
		assertThat(appliedCouponId(me)).as("a second working code replaces the first").isEqualTo(better.getId());
	}

	@Test
	void everyLookAtTheCartRechecksTheCouponAndTakesItOffWithTheReason() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setMinOrder(new BigDecimal("300")));
		CartOwner me = new CartOwner.Customer(customerWith(thali, 2)); // ₹500
		long id = ((CartOwner.Customer) me).userId();
		service.apply(id, coupon.getCode());

		carts.setQuantity(me, thali.getId(), 1, false); // ₹250
		CartCoupon box = service.forCart(me, carts.view(me));

		assertThat(box.applied()).isNull();
		assertThat(box.discount()).isEqualByComparingTo("0");
		assertThat(box.notice()).isEqualTo("We removed " + coupon.getCode() + ". Add ₹50 more to use "
				+ coupon.getCode() + ". It needs a minimum order of ₹300.");
		assertThat(appliedCouponId(id)).isNull();
		assertThat(service.forCart(me, carts.view(me)).notice()).as("said once").isNull();
	}

	@Test
	void aCouponThatExpiresWhileInTheCartIsTakenOff() {
		Coupon coupon = coupon(CouponType.FLAT, "50", c -> c.setValidity(NOW.minusDays(1), NOW.plusHours(1)));
		CartOwner me = new CartOwner.Customer(customerWith(thali, 1));
		service.apply(((CartOwner.Customer) me).userId(), coupon.getCode());

		clock.setTo(NOW.plusHours(2));
		CartCoupon box = service.forCart(me, carts.view(me));

		assertThat(box.applied()).isNull();
		assertThat(box.notice()).startsWith("We removed " + coupon.getCode() + ". " + coupon.getCode() + " expired on");
	}

	@Test
	void removingTheCouponOrEmptyingTheCartTakesItOff() {
		Coupon coupon = flat("50");
		long me = customerWith(thali, 1);
		service.apply(me, coupon.getCode());
		service.remove(me);
		assertThat(appliedCouponId(me)).isNull();

		service.apply(me, coupon.getCode());
		carts.setQuantity(new CartOwner.Customer(me), thali.getId(), 0, false);
		assertThat(appliedCouponId(me)).isNull();

		carts.setQuantity(new CartOwner.Customer(me), thali.getId(), 1, false);
		service.apply(me, coupon.getCode());
		carts.setQuantity(new CartOwner.Customer(me), misal.getId(), 1, true); // a new cart from another kitchen
		assertThat(appliedCouponId(me)).isNull();
	}

	@Test
	void guestsSeeTheOffersButCannotApplyOne() {
		GuestCart cart = new GuestCart();
		CartOwner guest = new CartOwner.Guest(cart);
		carts.setQuantity(guest, thali.getId(), 1, false);

		CartCoupon box = service.forCart(guest, carts.view(guest));

		assertThat(box.signedIn()).isFalse();
		assertThat(box.applied()).isNull();
		assertThat(box.offers()).isNotEmpty();
	}

	@Test
	void theOffersAreTheCouponsOpenAtThisRestaurantNowItsOwnFirst() {
		Coupon platform = flat("40");
		Coupon own = coupon(CouponType.PERCENT, "15", c -> c.setRestaurant(rassa));
		Coupon elsewhere = coupon(CouponType.FLAT, "40", c -> c.setRestaurant(sunrise));
		Coupon off = coupon(CouponType.FLAT, "40", c -> c.setActive(false));
		Coupon later = coupon(CouponType.FLAT, "40", c -> c.setValidity(NOW.plusDays(1), null));
		Coupon over = coupon(CouponType.FLAT, "40", c -> c.setValidity(NOW.minusDays(9), NOW.minusDays(1)));

		var codes = service.offers(rassa.getId()).stream().map(CouponOffer::code).toList();

		assertThat(codes).contains(platform.getCode(), own.getCode())
			.doesNotContain(elsewhere.getCode(), off.getCode(), later.getCode(), over.getCode());
		assertThat(codes.indexOf(own.getCode())).isLessThan(codes.indexOf(platform.getCode()));
	}

	@Test
	void offersReadLikeAProduct() {
		Coupon welcome = coupon(CouponType.PERCENT, "50", c -> {
			c.setMaxDiscount(new BigDecimal("100"));
			c.setMinOrder(new BigDecimal("149"));
			c.setFirstOrderOnly(true);
			c.setValidity(NOW.minusDays(1), LocalDateTime.of(2027, 4, 1, 0, 0));
		});
		assertThat(service.offers(rassa.getId())).filteredOn(o -> o.code().equals(welcome.getCode())).singleElement()
			.satisfies(offer -> {
				assertThat(offer.headline()).isEqualTo("50% off up to ₹100");
				assertThat(offer.description()).isEqualTo("A test offer.");
				assertThat(offer.terms()).isEqualTo("Min. order ₹149 · First order only · Valid till 31 Mar 2027");
			});
		assertThat(service.headline(flat("75"))).isEqualTo("₹75 off");
		assertThat(service.headline(coupon(CouponType.PERCENT, "12.5", c -> {
		}))).isEqualTo("12.5% off");
		assertThat(service.terms(flat("75"))).isEmpty();
	}

	@Test
	void theDatabaseRefusesBadCouponsToo() {
		String insert = "INSERT INTO coupons (code, description, discount_type, discount_value, min_order, valid_from, "
				+ "first_order_only, active, created_at, updated_at, version) VALUES (?, 'x', ?, ?, 0, ?, FALSE, TRUE, ?, ?, 0)";
		assertThatThrownBy(() -> jdbc.update(insert, "DBPCT", "PERCENT", 101, NOW, NOW, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(insert, "DBZERO", "FLAT", 0, NOW, NOW, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(insert, "DBKIND", "BOGO", 10, NOW, NOW, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
		Coupon taken = flat("10");
		assertThatThrownBy(() -> jdbc.update(insert, taken.getCode(), "FLAT", 10, NOW, NOW, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
