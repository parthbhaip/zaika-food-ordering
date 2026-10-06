package com.zaika.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.cart.CartView;
import com.zaika.cart.PriceBreakdown;
import com.zaika.cart.PricingService;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponService.CouponResult;
import com.zaika.user.Address;
import com.zaika.user.AddressLimitException;
import com.zaika.user.AddressService;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Turns a customer's cart into an order. Nothing the browser sends is trusted except the choices on the form: the
 * cart is re-checked against the menu, the restaurant must be approved, within its hours and accepting orders, the
 * minimum order must be met, the coupon is re-checked and its use recorded under a lock, and the bill is priced again
 * here. Everything happens in one transaction: the order, its dishes, the coupon use, a new address and the emptied
 * cart are saved together or not at all.
 *
 * <p>Double submits: the form's one-time key is stored on the order, and the customer's account row is locked first,
 * so a second submit of the same form waits for the first and then finds its order instead of placing another.
 */
@Service
@Transactional
public class CheckoutService {

	private final UserRepository users;

	private final OrderRepository orders;

	private final CartService carts;

	private final CouponService coupons;

	private final PricingService pricing;

	private final AddressService addresses;

	private final OrderWorkflow workflow;

	private final Formatters fmt;

	private final Clock clock;

	CheckoutService(UserRepository users, OrderRepository orders, CartService carts, CouponService coupons,
			PricingService pricing, AddressService addresses, OrderWorkflow workflow, Formatters fmt, Clock clock) {
		this.users = users;
		this.orders = orders;
		this.carts = carts;
		this.coupons = coupons;
		this.pricing = pricing;
		this.addresses = addresses;
		this.workflow = workflow;
		this.fmt = fmt;
		this.clock = clock;
	}

	/**
	 * Places the order. Cash on delivery goes straight to PLACED; online payment stays PENDING_PAYMENT until the
	 * payment page confirms it.
	 *
	 * @throws CheckoutException        with the reason to show, when the order cannot be placed as the customer saw it
	 * @throws NotFoundException        if {@code addressId} is not one of the customer's addresses
	 * @throws IllegalArgumentException if the form has no valid key (the controller checks it first)
	 */
	public Placement placeOrder(long userId, CheckoutForm form) {
		if (!form.hasValidKey()) {
			throw new IllegalArgumentException("Missing checkout key");
		}
		User customer = users.findForUpdate(userId).orElseThrow(() -> new NotFoundException("User " + userId));
		Optional<Order> placed = orders.findByCustomerIdAndIdempotencyKey(userId, form.getIdempotencyKey());
		if (placed.isPresent()) {
			return new Placement(placed.get(), true);
		}

		CartOwner owner = new CartOwner.Customer(userId);
		CartView cart = carts.view(owner);
		checkCart(cart);
		if (!cart.notices().isEmpty()) {
			throw new CheckoutException("Your cart changed while you were checking out. Check your order and the bill, "
					+ "then place it again.", false);
		}

		Optional<CouponResult> coupon = coupons.redeem(userId, cart);
		if (coupon.isPresent() && !coupon.get().applied()) {
			throw new CheckoutException("We could not use your coupon. " + coupon.get().message()
					+ " Check the new total, then place your order again.", false);
		}
		PriceBreakdown bill = pricing.price(cart, coupon.map(CouponResult::discount).orElse(BigDecimal.ZERO));
		if (form.getExpectedTotal() == null || bill.grandTotal().compareTo(form.getExpectedTotal()) != 0) {
			throw new CheckoutException("Your total is now " + fmt.money(bill.grandTotal())
					+ ". Check the bill, then place your order again.", false);
		}

		Address address = deliveryAddress(userId, form);
		Restaurant restaurant = cart.restaurant();
		LocalDateTime eta = LocalDateTime.now(clock).plusMinutes(restaurant.getDeliveryMinutes());
		Order order = new Order(customer, restaurant, DeliveryAddress.of(address), form.getPaymentMethod(), bill,
				coupon.map(CouponResult::code).orElse(null), eta);
		order.setIdempotencyKey(form.getIdempotencyKey());
		order.setRestaurantNote(cart.instructions());
		order.setDeliveryNote(form.getDeliveryNote());
		cart.lines().forEach(line -> order.addItem(line.item(), line.quantity()));
		orders.save(order);
		coupon.ifPresent(redeemed -> coupons.linkToOrder(redeemed, order));
		if (form.getPaymentMethod() == PaymentMethod.COD) {
			workflow.transition(order, OrderStatus.PLACED, OrderActor.system());
		}
		carts.clear(owner);
		return new Placement(order, false);
	}

	/** The rules on the cart itself; breaking one sends the customer back to the cart, which explains it. */
	private void checkCart(CartView cart) {
		if (cart.isEmpty()) {
			// a restaurant that is no longer approved empties the cart; the cart page shows that notice itself
			throw new CheckoutException(cart.notices().isEmpty() ? "Your cart is empty, so there is nothing to check out."
					: null, true);
		}
		Restaurant restaurant = cart.restaurant();
		if (restaurant.getStatus() != RestaurantStatus.APPROVED) {
			throw new CheckoutException(restaurant.getName() + " is not taking orders on Zaika right now.", true);
		}
		if (!cart.takingOrders()) {
			throw new CheckoutException(cart.opensAt() != null
					? restaurant.getName() + " is closed now and opens at " + fmt.timeOfDay(cart.opensAt())
							+ ". Your cart is saved."
					: restaurant.getName() + " is not taking orders right now. Your cart is saved.", true);
		}
		if (cart.minimumOrderShortfall().signum() > 0) {
			throw new CheckoutException("Add " + fmt.price(cart.minimumOrderShortfall()) + " more to place this order. "
					+ restaurant.getName() + " takes orders from " + fmt.price(restaurant.getMinimumOrder()) + ".", true);
		}
	}

	private Address deliveryAddress(long userId, CheckoutForm form) {
		if (!form.wantsNewAddress()) {
			return addresses.get(userId, form.getAddressId());
		}
		try {
			return addresses.create(userId, form.getNewAddress());
		}
		catch (AddressLimitException ex) {
			throw new CheckoutException("Your address book is full (" + AddressService.MAX_ADDRESSES
					+ " addresses). Pick a saved address, or delete one to add another.", false);
		}
	}

	/** The order a checkout placed; {@code repeat} when this form had already placed it (a double submit). */
	public record Placement(Order order, boolean repeat) {
	}

}
