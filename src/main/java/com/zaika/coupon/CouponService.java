package com.zaika.coupon;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.cart.Cart;
import com.zaika.cart.CartOwner;
import com.zaika.cart.CartRepository;
import com.zaika.cart.CartService;
import com.zaika.cart.CartView;
import com.zaika.common.Formatters;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.user.UserRepository;

/**
 * Coupons in the cart: apply a code (with a specific message for every reason it does not work), remove it, re-check
 * the applied one on every look at the cart, list the offers for a restaurant, and record a use at checkout. Only
 * signed-in customers apply coupons (the per-customer and first-order rules need an account); the coupon is kept on
 * their saved {@link Cart}.
 */
@Service
@Transactional
public class CouponService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final CouponRepository coupons;

	private final CouponRedemptionRepository redemptions;

	private final CartRepository carts;

	private final CartService cartService;

	private final UserRepository users;

	private final OrderRepository orders;

	private final Formatters fmt;

	private final Clock clock;

	CouponService(CouponRepository coupons, CouponRedemptionRepository redemptions, CartRepository carts,
			CartService cartService, UserRepository users, OrderRepository orders, Formatters fmt, Clock clock) {
		this.orders = orders;
		this.coupons = coupons;
		this.redemptions = redemptions;
		this.carts = carts;
		this.cartService = cartService;
		this.users = users;
		this.fmt = fmt;
		this.clock = clock;
	}

	/**
	 * Applies {@code rawCode} to the customer's cart (replacing any coupon already there) if every rule passes.
	 * The cart is re-checked first, so the minimum order is measured against today's prices.
	 */
	public CouponResult apply(long userId, String rawCode) {
		String code = Coupon.normalizeCode(rawCode);
		if (code.isEmpty()) {
			return CouponResult.rejected(code, CouponProblem.BLANK, message(CouponProblem.BLANK, code, null, null));
		}
		CartView cart = cartService.view(new CartOwner.Customer(userId)); // locks the account row too
		if (cart.isEmpty()) {
			return CouponResult.rejected(code, CouponProblem.EMPTY_CART,
					message(CouponProblem.EMPTY_CART, code, null, null));
		}
		Optional<Coupon> found = code.length() > Coupon.MAX_CODE_LENGTH ? Optional.empty() : coupons.findByCode(code);
		if (found.isEmpty()) {
			return CouponResult.rejected(code, CouponProblem.UNKNOWN, message(CouponProblem.UNKNOWN, code, null, null));
		}
		Coupon coupon = found.get();
		CouponResult result = check(coupon, userId, cart);
		if (result.applied()) {
			carts.findByUserId(userId).ifPresent(saved -> saved.setCoupon(coupon));
		}
		return result;
	}

	/** Takes the coupon off the customer's cart. */
	public void remove(long userId) {
		carts.findByUserId(userId).ifPresent(saved -> saved.setCoupon(null));
	}

	/**
	 * The coupon box of the cart page for a re-checked cart: the applied coupon and its discount (re-checked, and
	 * taken off with a notice saying why once it no longer works), and the restaurant's offers. Guests see the
	 * offers only.
	 */
	public CartCoupon forCart(CartOwner owner, CartView cart) {
		if (cart.isEmpty()) {
			return new CartCoupon(null, null, List.of(), owner instanceof CartOwner.Customer);
		}
		List<CouponOffer> offers = offers(cart.restaurant().getId());
		if (!(owner instanceof CartOwner.Customer customer)) {
			return new CartCoupon(null, null, offers, false);
		}
		Cart saved = carts.findByUserId(customer.userId()).orElse(null);
		if (saved == null || saved.getCoupon() == null) {
			return new CartCoupon(null, null, offers, true);
		}
		Coupon coupon = saved.getCoupon();
		CouponResult result = check(coupon, customer.userId(), cart);
		if (!result.applied()) {
			saved.setCoupon(null);
			return new CartCoupon(null, "We removed " + coupon.getCode() + ". " + result.message(), offers, true);
		}
		return new CartCoupon(new CartCoupon.Applied(coupon.getCode(), headline(coupon), result.discount()), null,
				offers, true);
	}

	/**
	 * At checkout, inside the order's transaction: re-checks the cart's coupon under a row lock on the coupon (so two
	 * orders cannot both take its last use) and, if it still works, records the use. Empty when no coupon is
	 * applied; a rejected result means the order must not be placed with it.
	 */
	public Optional<CouponResult> redeem(long userId, CartView cart) {
		Coupon applied = cart.isEmpty() ? null : carts.findByUserId(userId).map(Cart::getCoupon).orElse(null);
		if (applied == null) {
			return Optional.empty();
		}
		Coupon coupon = coupons.findForUpdate(applied.getId()).orElseThrow();
		CouponResult result = check(coupon, userId, cart);
		if (result.applied()) {
			CouponRedemption use = redemptions
				.save(new CouponRedemption(coupon, users.getReferenceById(userId), result.discount()));
			result = result.recordedAs(use.getId());
		}
		return Optional.of(result);
	}

	/** Ties the use {@link #redeem} recorded to the order placed with it, so cancelling the order can give it back. */
	public void linkToOrder(CouponResult redeemed, Order order) {
		if (redeemed.redemptionId() == null) {
			throw new IllegalArgumentException("No coupon use was recorded for " + redeemed.code());
		}
		redemptions.findById(redeemed.redemptionId()).orElseThrow().setOrder(order);
	}

	/**
	 * Gives back the coupon use of an order that will never be fulfilled (cancelled, rejected, expired unpaid), so the
	 * coupon's total and per-customer limits count only live orders. Returns how many uses were released (0 or 1).
	 */
	public long release(Order order) {
		return redemptions.deleteByOrderId(order.getId());
	}

	/** Coupons open to everyone at this restaurant now (its own first), for the cart and the restaurant page. */
	@Transactional(readOnly = true)
	public List<CouponOffer> offers(long restaurantId) {
		return coupons.findOffers(restaurantId, LocalDateTime.now(clock)).stream()
			.map(c -> new CouponOffer(c.getCode(), headline(c), c.getDescription(), terms(c)))
			.toList();
	}

	/** "50% off up to ₹100", "20% off", "₹75 off". */
	public String headline(Coupon coupon) {
		if (coupon.getType() == CouponType.FLAT) {
			return fmt.price(coupon.getValue()) + " off";
		}
		String percent = coupon.getValue().stripTrailingZeros().toPlainString() + "% off";
		return coupon.getMaxDiscount() == null ? percent : percent + " up to " + fmt.price(coupon.getMaxDiscount());
	}

	/** The fine print: "Min. order ₹199 · First order only · Valid till 31 Mar 2027"; empty when there is none. */
	String terms(Coupon coupon) {
		List<String> parts = new ArrayList<>();
		if (coupon.getMinOrder().signum() > 0) {
			parts.add("Min. order " + fmt.price(coupon.getMinOrder()));
		}
		if (coupon.isFirstOrderOnly()) {
			parts.add("First order only");
		}
		if (coupon.getValidUntil() != null) { // the end is exclusive: the last valid day is the one before it
			parts.add("Valid till " + fmt.date(coupon.getValidUntil().minusNanos(1)));
		}
		return String.join(" · ", parts);
	}

	private CouponResult check(Coupon coupon, long userId, CartView cart) {
		long id = coupon.getId();
		Coupon.Usage usage = new Coupon.Usage(
				coupon.getPerUserLimit() == null ? 0 : redemptions.countByCouponIdAndUserId(id, userId),
				coupon.getTotalLimit() == null ? 0 : redemptions.countByCouponId(id),
				coupon.isFirstOrderOnly() && hasOrdered(userId));
		CouponProblem problem = coupon.problem(LocalDateTime.now(clock), cart.restaurant().getId(), cart.itemTotal(),
				usage);
		if (problem != null) {
			return CouponResult.rejected(coupon.getCode(), problem,
					message(problem, coupon.getCode(), coupon, cart.itemTotal()));
		}
		BigDecimal discount = coupon.discountOn(cart.itemTotal());
		return new CouponResult(coupon.getCode(), discount, null,
				coupon.getCode() + " applied. You save " + fmt.money(discount) + " on this order.", null);
	}

	/** Placed an order before that was not cancelled or rejected (an unpaid online order does not count). */
	private boolean hasOrdered(long userId) {
		return orders.existsByCustomerIdAndStatusIn(userId, OrderStatus.PLACED_AND_LIVE);
	}

	/** What the customer reads for each problem. {@code coupon} is {@code null} until one was found. */
	private String message(CouponProblem problem, String code, Coupon coupon, BigDecimal itemTotal) {
		return switch (problem) {
			case EMPTY_CART -> "Add a dish to your cart before applying a coupon.";
			case BLANK -> "Enter a coupon code.";
			case UNKNOWN -> "We could not find a coupon called " + shorten(code) + ". Check the code and try again.";
			case INACTIVE -> code + " is not available any more. Try another offer.";
			case NOT_STARTED -> code + " starts on " + fmt.dateTime(coupon.getValidFrom()) + ".";
			case EXPIRED -> code + " expired on " + fmt.date(coupon.getValidUntil().minusNanos(1)) + ".";
			case OTHER_RESTAURANT -> code + " works only on orders from " + coupon.getRestaurant().getName() + ".";
			case FIRST_ORDER_ONLY -> code + " is for your first order on Zaika.";
			case FULLY_CLAIMED -> code + " has been fully claimed. Try another offer.";
			case USED_UP -> coupon.getPerUserLimit() == 1 ? "You have already used " + code + "."
					: "You have used " + code + " " + coupon.getPerUserLimit() + " times, the most it allows.";
			case MIN_ORDER -> "Add " + fmt.price(coupon.getMinOrder().subtract(itemTotal)) + " more to use " + code
					+ ". It needs a minimum order of " + fmt.price(coupon.getMinOrder()) + ".";
		};
	}

	private static String shorten(String code) {
		return code.length() > Coupon.MAX_CODE_LENGTH ? code.substring(0, Coupon.MAX_CODE_LENGTH) + "…" : code;
	}

	/**
	 * The outcome of applying or re-checking a coupon: {@code problem} is {@code null} when it works, and then
	 * {@code discount} is what it takes off the item total. {@code message} is ready to show either way.
	 * {@code redemptionId} is the use {@link #redeem} recorded at checkout, else {@code null}.
	 */
	public record CouponResult(String code, BigDecimal discount, CouponProblem problem, String message,
			Long redemptionId) {

		static CouponResult rejected(String code, CouponProblem problem, String message) {
			return new CouponResult(code, ZERO, problem, message, null);
		}

		CouponResult recordedAs(long id) {
			return new CouponResult(code, discount, problem, message, id);
		}

		public boolean applied() {
			return problem == null;
		}

	}

	/** An offer as the cart and the restaurant page list it. {@code terms} may be empty. */
	public record CouponOffer(String code, String headline, String description, String terms) {
	}

	/**
	 * The cart page's coupon box.
	 *
	 * @param applied  the applied coupon, re-checked; {@code null} if none
	 * @param notice   why an applied coupon was just taken off, or {@code null}
	 * @param offers   coupons open at the cart's restaurant
	 * @param signedIn a customer (who can apply coupons) rather than a guest
	 */
	public record CartCoupon(Applied applied, String notice, List<CouponOffer> offers, boolean signedIn) {

		/** The discount to price the cart with: the applied coupon's, else zero. */
		public BigDecimal discount() {
			return applied == null ? ZERO : applied.discount();
		}

		public record Applied(String code, String headline, BigDecimal discount) {
		}

	}

}
