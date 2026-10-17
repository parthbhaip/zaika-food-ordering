package com.zaika.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.zaika.catalog.Restaurant;
import com.zaika.common.BaseEntity;

/**
 * A discount code: a percentage (optionally capped) or a flat amount off the item total, platform-wide or for one
 * restaurant, within a validity window, with optional total and per-customer limits and a first-order-only flag.
 * {@link #problem} holds the eligibility rules, {@link #discountOn} the amount.
 */
@Entity
@Table(name = "coupons")
public class Coupon extends BaseEntity {

	/** Upper-case letters and digits, 3 to 20 of them. */
	public static final String CODE_PATTERN = "[A-Z0-9]{3,20}";

	public static final int MAX_CODE_LENGTH = 20;

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	@NotNull
	@Pattern(regexp = CODE_PATTERN)
	@Column(nullable = false, length = MAX_CODE_LENGTH, unique = true, updatable = false)
	private String code;

	@NotBlank
	@Size(max = 160)
	@Column(nullable = false, length = 160)
	private String description;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "discount_type", nullable = false, length = 10)
	private CouponType type;

	@NotNull
	@Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
	private BigDecimal value;

	@Column(name = "max_discount", precision = 10, scale = 2)
	private BigDecimal maxDiscount;

	@NotNull
	@Column(name = "min_order", nullable = false, precision = 10, scale = 2)
	private BigDecimal minOrder = BigDecimal.ZERO.setScale(2);

	@NotNull
	@Column(name = "valid_from", nullable = false)
	private LocalDateTime validFrom;

	@Column(name = "valid_until")
	private LocalDateTime validUntil;

	@Min(1)
	@Column(name = "total_limit")
	private Integer totalLimit;

	@Min(1)
	@Column(name = "per_user_limit")
	private Integer perUserLimit;

	@Column(name = "first_order_only", nullable = false)
	private boolean firstOrderOnly;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "restaurant_id")
	private Restaurant restaurant;

	@Column(nullable = false)
	private boolean active = true;

	protected Coupon() {
	}

	/**
	 * @param code      normalized with {@link #normalizeCode}
	 * @param value     percent (1-100) for {@link CouponType#PERCENT}, rupees for {@link CouponType#FLAT}
	 * @param validFrom when the code starts working
	 */
	public Coupon(String code, String description, CouponType type, BigDecimal value, LocalDateTime validFrom) {
		this.code = normalizeCode(Objects.requireNonNull(code, "code"));
		this.description = description;
		this.type = Objects.requireNonNull(type, "type");
		this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
		setValue(value);
	}

	/** What a customer typed, as stored: no surrounding or inner spaces, upper-case. Blank becomes {@code ""}. */
	public static String normalizeCode(String raw) {
		return raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}

	/**
	 * Why this coupon cannot be used on a cart, or {@code null} if it can. Checked in this order, so the customer
	 * hears the reason they can do least about first: switched off, expired, not started, another restaurant, first
	 * order only, all claimed, used up by this customer, and last the one they can fix, the minimum order.
	 *
	 * @param restaurantId the restaurant of the cart's dishes
	 * @param itemTotal    the cart's item total before any discount
	 */
	public CouponProblem problem(LocalDateTime now, long restaurantId, BigDecimal itemTotal, Usage usage) {
		if (!active) {
			return CouponProblem.INACTIVE;
		}
		if (validUntil != null && !now.isBefore(validUntil)) {
			return CouponProblem.EXPIRED;
		}
		if (now.isBefore(validFrom)) {
			return CouponProblem.NOT_STARTED;
		}
		if (restaurant != null && restaurant.getId() != restaurantId) {
			return CouponProblem.OTHER_RESTAURANT;
		}
		if (firstOrderOnly && usage.orderedBefore()) {
			return CouponProblem.FIRST_ORDER_ONLY;
		}
		if (totalLimit != null && usage.total() >= totalLimit) {
			return CouponProblem.FULLY_CLAIMED;
		}
		if (perUserLimit != null && usage.byCustomer() >= perUserLimit) {
			return CouponProblem.USED_UP;
		}
		if (itemTotal.compareTo(minOrder) < 0) {
			return CouponProblem.MIN_ORDER;
		}
		return null;
	}

	/**
	 * The discount on this item total: the percentage (rounded HALF_UP to paise, then capped at the maximum
	 * discount) or the flat amount, never more than the item total itself.
	 */
	public BigDecimal discountOn(BigDecimal itemTotal) {
		BigDecimal items = itemTotal.setScale(2, RoundingMode.HALF_UP);
		BigDecimal off = type == CouponType.PERCENT
				? items.multiply(value).divide(HUNDRED, 2, RoundingMode.HALF_UP)
				: value;
		if (maxDiscount != null) {
			off = off.min(maxDiscount);
		}
		return off.min(items);
	}

	/** Usage facts for {@link #problem}: this customer's uses, everyone's uses, and whether they ordered before. */
	public record Usage(long byCustomer, long total, boolean orderedBefore) {
	}

	public String getCode() {
		return code;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public CouponType getType() {
		return type;
	}

	public BigDecimal getValue() {
		return value;
	}

	/** Changes the kind of discount and its value together (the value is read by the type). */
	public void setDiscount(CouponType type, BigDecimal value) {
		this.type = Objects.requireNonNull(type, "type");
		setValue(value);
	}

	public void setValue(BigDecimal value) {
		Objects.requireNonNull(value, "value");
		if (value.signum() <= 0 || (type == CouponType.PERCENT && value.compareTo(HUNDRED) > 0)) {
			throw new IllegalArgumentException("Coupon value out of range: " + value);
		}
		this.value = value.setScale(2, RoundingMode.HALF_UP);
	}

	public BigDecimal getMaxDiscount() {
		return maxDiscount;
	}

	public void setMaxDiscount(BigDecimal maxDiscount) {
		this.maxDiscount = maxDiscount == null ? null : maxDiscount.setScale(2, RoundingMode.HALF_UP);
	}

	public BigDecimal getMinOrder() {
		return minOrder;
	}

	public void setMinOrder(BigDecimal minOrder) {
		this.minOrder = Objects.requireNonNull(minOrder, "minOrder").setScale(2, RoundingMode.HALF_UP);
	}

	public LocalDateTime getValidFrom() {
		return validFrom;
	}

	public LocalDateTime getValidUntil() {
		return validUntil;
	}

	/** {@code until} is exclusive; {@code null} means the coupon never expires. */
	public void setValidity(LocalDateTime from, LocalDateTime until) {
		Objects.requireNonNull(from, "from");
		if (until != null && !until.isAfter(from)) {
			throw new IllegalArgumentException("A coupon must end after it starts");
		}
		this.validFrom = from;
		this.validUntil = until;
	}

	public Integer getTotalLimit() {
		return totalLimit;
	}

	public void setTotalLimit(Integer totalLimit) {
		this.totalLimit = totalLimit;
	}

	public Integer getPerUserLimit() {
		return perUserLimit;
	}

	public void setPerUserLimit(Integer perUserLimit) {
		this.perUserLimit = perUserLimit;
	}

	public boolean isFirstOrderOnly() {
		return firstOrderOnly;
	}

	public void setFirstOrderOnly(boolean firstOrderOnly) {
		this.firstOrderOnly = firstOrderOnly;
	}

	/** The one restaurant this coupon works at, or {@code null} for every restaurant. */
	public Restaurant getRestaurant() {
		return restaurant;
	}

	public void setRestaurant(Restaurant restaurant) {
		this.restaurant = restaurant;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

}
