package com.zaika.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.common.NotFoundException;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRedemptionRepository;
import com.zaika.coupon.CouponRedemptionRepository.Usage;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponService;

/**
 * Coupons for the admin: the list with each coupon's usage, create, edit, and delete. A coupon that was ever used on
 * a live order cannot be deleted (its uses point at it); it is switched off instead. Uses given back by cancelled,
 * rejected or expired orders do not count.
 */
@Service
@Transactional
public class CouponAdminService {

	public static final int PAGE_SIZE = 20;

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final CouponRepository coupons;

	private final CouponRedemptionRepository redemptions;

	private final RestaurantRepository restaurants;

	private final CouponService couponService;

	private final Clock clock;

	CouponAdminService(CouponRepository coupons, CouponRedemptionRepository redemptions,
			RestaurantRepository restaurants, CouponService couponService, Clock clock) {
		this.coupons = coupons;
		this.redemptions = redemptions;
		this.restaurants = restaurants;
		this.couponService = couponService;
		this.clock = clock;
	}

	/** One page of coupons, newest first, each with its usage and state (3 statements). {@code page} is 1-based. */
	@Transactional(readOnly = true)
	public Page<Row> list(int page) {
		Page<Coupon> found = coupons.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page - 1, PAGE_SIZE));
		Map<Long, Usage> usage = found.isEmpty() ? Map.of()
				: redemptions.usageOf(found.map(Coupon::getId).toList()).stream()
					.collect(Collectors.toMap(Usage::couponId, Function.identity()));
		LocalDateTime now = LocalDateTime.now(clock);
		return found.map(c -> row(c, usage.get(c.getId()), now));
	}

	/** The edit page's coupon with its usage; unknown ids are a 404. */
	@Transactional(readOnly = true)
	public Row detail(long id) {
		Coupon c = coupons.findWithRestaurantById(id).orElseThrow(() -> new NotFoundException("No coupon " + id));
		List<Usage> usage = redemptions.usageOf(List.of(id));
		return row(c, usage.isEmpty() ? null : usage.getFirst(), LocalDateTime.now(clock));
	}

	/** Every restaurant by name, for the "Works at" choice. */
	@Transactional(readOnly = true)
	public List<Restaurant> restaurants() {
		return restaurants.findAll(Sort.by("name", "id"));
	}

	/** A new coupon from a valid form; returns its code. A taken code or unknown restaurant -> {@link FieldRuleException}. */
	public String create(CouponForm form) {
		String code = Coupon.normalizeCode(form.getCode());
		if (coupons.findByCode(code).isPresent()) {
			throw new FieldRuleException("code", code + " is already taken. Pick another code.");
		}
		Coupon coupon = new Coupon(code, form.getDescription(), form.getType(), form.getValue(), form.getValidFrom());
		apply(coupon, form);
		coupons.saveAndFlush(coupon); // a code taken meanwhile fails here, inside the transaction
		return code;
	}

	/** Saves a valid form over the coupon (everything but its code); returns the code. */
	public String update(long id, CouponForm form) {
		Coupon coupon = coupons.findById(id).orElseThrow(() -> new NotFoundException("No coupon " + id));
		coupon.setDiscount(form.getType(), form.getValue());
		coupon.setDescription(form.getDescription());
		apply(coupon, form);
		return coupon.getCode();
	}

	/**
	 * Deletes a coupon nobody has used; returns its code. The coupon row is locked first, the same lock checkout takes
	 * before recording a use, so no use can slip in between the check and the delete.
	 */
	public String delete(long id) {
		Coupon coupon = coupons.findForUpdate(id).orElseThrow(() -> new NotFoundException("No coupon " + id));
		long uses = redemptions.countByCouponId(id);
		if (uses > 0) {
			throw new ModerationException(coupon.getCode() + " was used on " + (uses == 1 ? "one order" : uses + " orders")
					+ ", so it stays for the records. Switch it off instead.");
		}
		coupons.delete(coupon); // carts holding it lose it (ON DELETE SET NULL)
		return coupon.getCode();
	}

	private void apply(Coupon coupon, CouponForm form) {
		coupon.setMaxDiscount(form.getMaxDiscount());
		coupon.setMinOrder(form.getMinOrder() == null ? BigDecimal.ZERO : form.getMinOrder());
		coupon.setValidity(form.getValidFrom(), form.getValidUntil());
		coupon.setTotalLimit(form.getTotalLimit());
		coupon.setPerUserLimit(form.getPerUserLimit());
		coupon.setFirstOrderOnly(form.isFirstOrderOnly());
		coupon.setActive(form.isActive());
		coupon.setRestaurant(form.getRestaurantId() == null ? null
				: restaurants.findById(form.getRestaurantId())
					.orElseThrow(() -> new FieldRuleException("restaurantId", "Choose a restaurant from the list.")));
	}

	private Row row(Coupon c, Usage usage, LocalDateTime now) {
		long uses = usage == null ? 0 : usage.uses();
		return new Row(c, couponService.headline(c), couponService.terms(c), uses,
				usage == null ? 0 : usage.customers(), usage == null ? ZERO : usage.discount(), state(c, uses, now));
	}

	/** Where a coupon stands now, in the order a customer would hit it. */
	static State state(Coupon c, long uses, LocalDateTime now) {
		if (!c.isActive()) {
			return State.OFF;
		}
		if (c.getValidUntil() != null && !now.isBefore(c.getValidUntil())) {
			return State.ENDED;
		}
		if (now.isBefore(c.getValidFrom())) {
			return State.SCHEDULED;
		}
		if (c.getTotalLimit() != null && uses >= c.getTotalLimit()) {
			return State.FULLY_CLAIMED;
		}
		return State.LIVE;
	}

	/** A coupon's state as the admin pages badge it. */
	public enum State {

		LIVE("Live", "badge-success"), SCHEDULED("Scheduled", "badge-warning"), FULLY_CLAIMED("Fully claimed", ""),
		ENDED("Ended", ""), OFF("Off", "badge-danger");

		private final String label;

		private final String badge;

		State(String label, String badge) {
			this.label = label;
			this.badge = badge;
		}

		public String getLabel() {
			return label;
		}

		/** The extra badge class. */
		public String getBadge() {
			return badge;
		}

	}

	/**
	 * A coupon with what the customer reads ({@code headline} "50% off up to ₹100", {@code terms} the fine print, maybe
	 * empty) and its usage on live orders: uses, distinct customers and the discount given.
	 */
	public record Row(Coupon coupon, String headline, String terms, long uses, long customers, BigDecimal discount,
			State state) {
	}

}
