package com.zaika.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.Errors;

import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponType;

/**
 * A coupon as the admin creates or edits it. Times are Asia/Kolkata wall-clock times from {@code datetime-local}
 * inputs; "ends" is exclusive. The code is fixed once the coupon exists (the edit page shows it read-only and the
 * service ignores it). Rules that span fields live in {@link #check}; the taken code and an unknown restaurant are
 * checked by the service.
 */
public class CouponForm {

	static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	@NotBlank(message = "Give the coupon a code, like DIWALI100.")
	@Pattern(regexp = Coupon.CODE_PATTERN, message = "Use 3 to 20 letters and digits, no spaces or symbols.")
	private String code;

	@NotBlank(message = "Write a line customers see under the code, like Flat ₹100 off on Diwali orders.")
	@Size(max = 160, message = "Keep the description under 160 characters.")
	private String description;

	@NotNull(message = "Choose a percentage or a flat amount.")
	private CouponType type = CouponType.PERCENT;

	@NotNull(message = "Enter the discount.")
	@DecimalMin(value = "0.01", message = "The discount must be more than zero.")
	@Digits(integer = 6, fraction = 2, message = "Use an amount like 50 or 99.50.")
	private BigDecimal value;

	@DecimalMin(value = "0.01", message = "The cap must be more than zero, or leave it empty.")
	@Digits(integer = 6, fraction = 2, message = "Use an amount like 100 or 149.50.")
	private BigDecimal maxDiscount;

	@DecimalMin(value = "0", message = "The minimum order cannot be negative.")
	@Digits(integer = 6, fraction = 2, message = "Use an amount like 199 or 249.50.")
	private BigDecimal minOrder;

	@NotNull(message = "Pick when the coupon starts working.")
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
	private LocalDateTime validFrom;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
	private LocalDateTime validUntil;

	@Min(value = 1, message = "Allow at least 1 use, or leave it empty for no limit.")
	@Max(value = 1_000_000, message = "Keep the limit to 10,00,000 or less, or leave it empty for no limit.")
	private Integer totalLimit;

	@Min(value = 1, message = "Allow at least 1 use per customer, or leave it empty for no limit.")
	@Max(value = 1_000, message = "Keep it to 1,000 uses per customer or fewer, or leave it empty for no limit.")
	private Integer perUserLimit;

	private boolean firstOrderOnly;

	/** Null for every restaurant. */
	private Long restaurantId;

	private boolean active = true;

	public CouponForm() {
	}

	/** A new coupon's defaults: a percentage, working from the start of this minute, no end, switched on. */
	static CouponForm starting(LocalDateTime now) {
		CouponForm form = new CouponForm();
		form.validFrom = now.truncatedTo(ChronoUnit.MINUTES);
		form.perUserLimit = 1;
		return form;
	}

	/** The edit form, filled from the coupon. */
	static CouponForm of(Coupon c) {
		CouponForm form = new CouponForm();
		form.code = c.getCode();
		form.description = c.getDescription();
		form.type = c.getType();
		form.value = plain(c.getValue());
		form.maxDiscount = plain(c.getMaxDiscount());
		form.minOrder = plain(c.getMinOrder());
		form.validFrom = c.getValidFrom().truncatedTo(ChronoUnit.MINUTES);
		form.validUntil = c.getValidUntil() == null ? null : c.getValidUntil().truncatedTo(ChronoUnit.MINUTES);
		form.totalLimit = c.getTotalLimit();
		form.perUserLimit = c.getPerUserLimit();
		form.firstOrderOnly = c.isFirstOrderOnly();
		form.restaurantId = c.getRestaurant() == null ? null : c.getRestaurant().getId();
		form.active = c.isActive();
		return form;
	}

	/** "50" rather than "50.00" in the inputs; null stays null. */
	private static BigDecimal plain(BigDecimal amount) {
		if (amount == null) {
			return null;
		}
		BigDecimal stripped = amount.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	/** The rules between fields, after Bean Validation. */
	void check(Errors errors) {
		if (type == CouponType.PERCENT && value != null && value.compareTo(HUNDRED) > 0
				&& !errors.hasFieldErrors("value")) {
			errors.rejectValue("value", "range", "A percentage goes up to 100.");
		}
		if (type == CouponType.FLAT && maxDiscount != null && !errors.hasFieldErrors("maxDiscount")) {
			errors.rejectValue("maxDiscount", "flat", "A cap only applies to percentage coupons. Leave it empty.");
		}
		if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)
				&& !errors.hasFieldErrors("validUntil")) {
			errors.rejectValue("validUntil", "order", "The end must be after the start.");
		}
		if (totalLimit != null && perUserLimit != null && perUserLimit > totalLimit
				&& !errors.hasFieldErrors("perUserLimit")) {
			errors.rejectValue("perUserLimit", "range", "A customer cannot use it more often than the total limit.");
		}
	}

	public String getCode() {
		return code;
	}

	/** Stored as typed by customers: no spaces, upper-case. */
	public void setCode(String code) {
		this.code = code == null || code.isBlank() ? null : Coupon.normalizeCode(code);
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description == null || description.isBlank() ? null : description.strip();
	}

	public CouponType getType() {
		return type;
	}

	public void setType(CouponType type) {
		this.type = type;
	}

	public BigDecimal getValue() {
		return value;
	}

	public void setValue(BigDecimal value) {
		this.value = value;
	}

	public BigDecimal getMaxDiscount() {
		return maxDiscount;
	}

	public void setMaxDiscount(BigDecimal maxDiscount) {
		this.maxDiscount = maxDiscount;
	}

	public BigDecimal getMinOrder() {
		return minOrder;
	}

	public void setMinOrder(BigDecimal minOrder) {
		this.minOrder = minOrder;
	}

	public LocalDateTime getValidFrom() {
		return validFrom;
	}

	public void setValidFrom(LocalDateTime validFrom) {
		this.validFrom = validFrom;
	}

	public LocalDateTime getValidUntil() {
		return validUntil;
	}

	public void setValidUntil(LocalDateTime validUntil) {
		this.validUntil = validUntil;
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

	public Long getRestaurantId() {
		return restaurantId;
	}

	public void setRestaurantId(Long restaurantId) {
		this.restaurantId = restaurantId;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

}
