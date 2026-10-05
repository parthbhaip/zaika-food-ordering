package com.zaika.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.zaika.common.BaseEntity;
import com.zaika.user.Address;
import com.zaika.user.Role;
import com.zaika.user.User;

/**
 * A restaurant on the marketplace, owned by exactly one {@link Role#RESTAURANT_OWNER} account. Addressed by its
 * slug, which never changes. New restaurants start {@link RestaurantStatus#PENDING}, accepting orders, unrated.
 * Money is stored at scale 2 (HALF_UP).
 */
@Entity
@Table(name = "restaurants")
public class Restaurant extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false, updatable = false, unique = true)
	private User owner;

	@Pattern(regexp = Slugs.PATTERN)
	@Size(max = 80)
	@Column(nullable = false, length = 80, unique = true, updatable = false)
	private String slug;

	@NotBlank
	@Size(max = 80)
	@Column(nullable = false, length = 80)
	private String name;

	@Size(max = 120)
	@Column(length = 120)
	private String tagline;

	@Size(max = 1000)
	@Column(length = 1000)
	private String description;

	@ManyToMany
	@JoinTable(name = "restaurant_cuisines", joinColumns = @JoinColumn(name = "restaurant_id"),
			inverseJoinColumns = @JoinColumn(name = "cuisine_id"))
	@OrderBy("name")
	private Set<Cuisine> cuisines = new LinkedHashSet<>();

	@NotBlank
	@Pattern(regexp = User.PHONE_PATTERN)
	@Column(nullable = false, length = 10)
	private String phone;

	@NotBlank
	@Size(max = 200)
	@Column(nullable = false, length = 200)
	private String address;

	@NotBlank
	@Size(max = 60)
	@Column(nullable = false, length = 60)
	private String area;

	@NotBlank
	@Size(max = 60)
	@Column(nullable = false, length = 60)
	private String city;

	@NotBlank
	@Pattern(regexp = Address.PINCODE_PATTERN)
	@Column(nullable = false, length = 6)
	private String pincode;

	@Size(max = 255)
	@Column(name = "logo_path")
	private String logoPath;

	@Size(max = 255)
	@Column(name = "cover_path")
	private String coverPath;

	@NotNull
	@DecimalMin("0.00")
	@Column(name = "cost_for_two", nullable = false, precision = 10, scale = 2)
	private BigDecimal costForTwo;

	@NotNull
	@DecimalMin("0.00")
	@Column(name = "minimum_order", nullable = false, precision = 10, scale = 2)
	private BigDecimal minimumOrder = money(BigDecimal.ZERO);

	@NotNull
	@DecimalMin("0.00")
	@Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
	private BigDecimal deliveryFee = money(BigDecimal.ZERO);

	@Min(1)
	@Max(180)
	@Column(name = "avg_prep_minutes", nullable = false)
	private int avgPrepMinutes = 20;

	@NotNull
	@Column(name = "opens_at", nullable = false)
	private LocalTime opensAt;

	@NotNull
	@Column(name = "closes_at", nullable = false)
	private LocalTime closesAt;

	@Column(name = "accepting_orders", nullable = false)
	private boolean acceptingOrders = true;

	@Column(name = "pure_veg", nullable = false)
	private boolean pureVeg;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RestaurantStatus status = RestaurantStatus.PENDING;

	@Size(max = 500)
	@Column(name = "status_reason", length = 500)
	private String statusReason;

	@NotNull
	@DecimalMin("0.00")
	@DecimalMax("5.00")
	@Column(name = "rating_average", nullable = false, precision = 3, scale = 2)
	private BigDecimal ratingAverage = BigDecimal.ZERO.setScale(2);

	@Min(0)
	@Column(name = "rating_count", nullable = false)
	private int ratingCount;

	protected Restaurant() {
	}

	/** The other required details (contact, address, cost for two, hours) are set before saving. */
	public Restaurant(User owner, String slug, String name) {
		Objects.requireNonNull(owner, "owner");
		if (owner.getRole() != Role.RESTAURANT_OWNER) {
			throw new IllegalArgumentException("A restaurant's owner must have the RESTAURANT_OWNER role");
		}
		this.owner = owner;
		this.slug = slug;
		this.name = name;
	}

	/**
	 * Open at {@code time} by the daily hours. Hours past midnight work: 18:00-02:00 is open from 6 PM to 2 AM.
	 * Equal opening and closing times mean open round the clock. Closing time itself counts as closed.
	 */
	public boolean isOpenAt(LocalTime time) {
		if (opensAt.equals(closesAt)) {
			return true;
		}
		boolean afterOpening = !time.isBefore(opensAt);
		boolean beforeClosing = time.isBefore(closesAt);
		return opensAt.isBefore(closesAt) ? afterOpening && beforeClosing : afterOpening || beforeClosing;
	}

	/** Open right now (IST, from the app clock) by the daily hours. Says nothing about the accepting-orders toggle. */
	public boolean isOpenNow(Clock clock) {
		return isOpenAt(LocalTime.now(clock));
	}

	/** Moves to {@code status}; REJECTED and SUSPENDED need a reason, the other states clear it. */
	public void changeStatus(RestaurantStatus status, String reason) {
		Objects.requireNonNull(status, "status");
		if (status.needsReason() && (reason == null || reason.isBlank())) {
			throw new IllegalArgumentException(status + " needs a reason");
		}
		this.status = status;
		this.statusReason = status.needsReason() ? reason.strip() : null;
	}

	/** Stores a recomputed rating: average of {@code count} reviews, rounded to 2 decimals. */
	public void setRating(BigDecimal average, int count) {
		this.ratingAverage = average.setScale(2, RoundingMode.HALF_UP);
		this.ratingCount = count;
	}

	public void setCuisines(Collection<Cuisine> cuisines) {
		this.cuisines.clear();
		this.cuisines.addAll(cuisines);
	}

	public void setHours(LocalTime opensAt, LocalTime closesAt) {
		this.opensAt = Objects.requireNonNull(opensAt, "opensAt");
		this.closesAt = Objects.requireNonNull(closesAt, "closesAt");
	}

	private static BigDecimal money(BigDecimal amount) {
		return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
	}

	public User getOwner() {
		return owner;
	}

	public String getSlug() {
		return slug;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTagline() {
		return tagline;
	}

	public void setTagline(String tagline) {
		this.tagline = tagline;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/** Ordered by name. */
	public Set<Cuisine> getCuisines() {
		return Collections.unmodifiableSet(cuisines);
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	/** Street address (shop number, building, road), without area, city and PIN. */
	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getArea() {
		return area;
	}

	public void setArea(String area) {
		this.area = area;
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getPincode() {
		return pincode;
	}

	public void setPincode(String pincode) {
		this.pincode = pincode;
	}

	public String getLogoPath() {
		return logoPath;
	}

	public void setLogoPath(String logoPath) {
		this.logoPath = logoPath;
	}

	public String getCoverPath() {
		return coverPath;
	}

	public void setCoverPath(String coverPath) {
		this.coverPath = coverPath;
	}

	public BigDecimal getCostForTwo() {
		return costForTwo;
	}

	public void setCostForTwo(BigDecimal costForTwo) {
		this.costForTwo = money(costForTwo);
	}

	public BigDecimal getMinimumOrder() {
		return minimumOrder;
	}

	public void setMinimumOrder(BigDecimal minimumOrder) {
		this.minimumOrder = money(minimumOrder);
	}

	public BigDecimal getDeliveryFee() {
		return deliveryFee;
	}

	public void setDeliveryFee(BigDecimal deliveryFee) {
		this.deliveryFee = money(deliveryFee);
	}

	public int getAvgPrepMinutes() {
		return avgPrepMinutes;
	}

	public void setAvgPrepMinutes(int avgPrepMinutes) {
		this.avgPrepMinutes = avgPrepMinutes;
	}

	public LocalTime getOpensAt() {
		return opensAt;
	}

	public LocalTime getClosesAt() {
		return closesAt;
	}

	public boolean isAcceptingOrders() {
		return acceptingOrders;
	}

	public void setAcceptingOrders(boolean acceptingOrders) {
		this.acceptingOrders = acceptingOrders;
	}

	public boolean isPureVeg() {
		return pureVeg;
	}

	public void setPureVeg(boolean pureVeg) {
		this.pureVeg = pureVeg;
	}

	public RestaurantStatus getStatus() {
		return status;
	}

	/** Why the restaurant was rejected or suspended; null otherwise. */
	public String getStatusReason() {
		return statusReason;
	}

	public BigDecimal getRatingAverage() {
		return ratingAverage;
	}

	public int getRatingCount() {
		return ratingCount;
	}

}
