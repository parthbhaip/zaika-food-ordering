package com.zaika.owner;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.Restaurant;
import com.zaika.user.Address;
import com.zaika.user.User;

/**
 * A restaurant's details, cuisines, photos, hours and fees: the owner's "Restaurant profile" form
 * ({@code /owner/restaurant}) and the restaurant half of the sign-up form ({@link RestaurantSignupForm}, which offers
 * only a logo). The phone and PIN code are bound in their stored form.
 */
public class RestaurantProfileForm {

	/** A restaurant is listed under at most this many cuisines (they show on its card). */
	public static final int MAX_CUISINES = 3;

	/** The usual preparation times offered in the form, in minutes. */
	public static final List<Integer> PREP_MINUTES = List.of(10, 15, 20, 25, 30, 40, 45, 60);

	@NotBlank(message = "Enter your restaurant's name.")
	@Size(max = 80, message = "Keep the name under 80 characters.")
	@Pattern(regexp = ".*\\p{Alnum}.*", message = "Use letters or digits in the name.")
	private String restaurantName;

	@Size(max = 120, message = "Keep the tagline under 120 characters.")
	private String tagline;

	@Size(max = 1000, message = "Keep the description under 1000 characters.")
	private String description;

	@NotBlank(message = "Enter the number customers and riders should call.")
	@Pattern(regexp = User.PHONE_PATTERN, message = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.")
	private String restaurantPhone;

	@NotBlank(message = "Enter the shop number, building and road.")
	@Size(max = 200, message = "Keep the address under 200 characters.")
	private String address;

	@NotBlank(message = "Enter the area, like Rajarampuri.")
	@Size(max = 60, message = "Keep the area under 60 characters.")
	private String area;

	@NotBlank(message = "Enter the city.")
	@Size(max = 60, message = "Keep the city under 60 characters.")
	private String city = "Kolhapur";

	@NotBlank(message = "Enter the 6-digit PIN code.")
	@Pattern(regexp = Address.PINCODE_PATTERN, message = "Enter a valid 6-digit PIN code, like 416008.")
	private String pincode;

	@Size(min = 1, max = MAX_CUISINES, message = "Pick between 1 and 3 cuisines.")
	private List<Long> cuisineIds = new ArrayList<>();

	private boolean pureVeg;

	private MultipartFile logo;

	/** Profile only: take the current logo off (ignored when a new one is attached). */
	private boolean removeLogo;

	/** Profile only: a new cover photo. */
	private MultipartFile cover;

	/** Profile only: take the current cover photo off (ignored when a new one is attached). */
	private boolean removeCover;

	@NotNull(message = "Enter your opening time.")
	@DateTimeFormat(pattern = "HH:mm")
	private LocalTime opensAt = LocalTime.of(11, 0);

	@NotNull(message = "Enter your closing time.")
	@DateTimeFormat(pattern = "HH:mm")
	private LocalTime closesAt = LocalTime.of(23, 0);

	@Min(value = 5, message = "Choose a preparation time.")
	@Max(value = 90, message = "Choose a preparation time.")
	private int avgPrepMinutes = 20;

	@NotNull(message = "Enter the usual cost for two, like 400.")
	@DecimalMin(value = "50.00", message = "Cost for two should be at least ₹50.")
	@DecimalMax(value = "10000.00", message = "Cost for two can be at most ₹10,000.")
	@Digits(integer = 5, fraction = 2, message = "Use rupees and up to 2 decimals, like 400 or 399.50.")
	private BigDecimal costForTwo;

	@NotNull(message = "Enter a minimum order, or 0 for none.")
	@DecimalMin(value = "0.00", message = "The minimum order cannot be negative.")
	@DecimalMax(value = "2000.00", message = "Keep the minimum order at ₹2,000 or less.")
	@Digits(integer = 4, fraction = 2, message = "Use rupees and up to 2 decimals, like 150.")
	private BigDecimal minimumOrder = BigDecimal.ZERO;

	@NotNull(message = "Enter a delivery fee, or 0 for free delivery.")
	@DecimalMin(value = "0.00", message = "The delivery fee cannot be negative.")
	@DecimalMax(value = "200.00", message = "Keep the delivery fee at ₹200 or less.")
	@Digits(integer = 3, fraction = 2, message = "Use rupees and up to 2 decimals, like 30.")
	private BigDecimal deliveryFee = new BigDecimal("30");

	/** The form for this restaurant as it is now (cuisines must be loaded). */
	static RestaurantProfileForm of(Restaurant r) {
		RestaurantProfileForm form = new RestaurantProfileForm();
		form.restaurantName = r.getName();
		form.tagline = r.getTagline();
		form.description = r.getDescription();
		form.restaurantPhone = r.getPhone();
		form.address = r.getAddress();
		form.area = r.getArea();
		form.city = r.getCity();
		form.pincode = r.getPincode();
		form.cuisineIds = new ArrayList<>(r.getCuisines().stream().map(Cuisine::getId).toList());
		form.pureVeg = r.isPureVeg();
		form.opensAt = r.getOpensAt();
		form.closesAt = r.getClosesAt();
		form.avgPrepMinutes = r.getAvgPrepMinutes();
		form.costForTwo = r.getCostForTwo();
		form.minimumOrder = r.getMinimumOrder();
		form.deliveryFee = r.getDeliveryFee();
		return form;
	}

	/** A logo was attached. */
	public boolean hasLogo() {
		return logo != null && !logo.isEmpty();
	}

	/** A cover photo was attached. */
	public boolean hasCover() {
		return cover != null && !cover.isEmpty();
	}

	static String trimToNull(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	public String getRestaurantName() {
		return restaurantName;
	}

	public void setRestaurantName(String restaurantName) {
		this.restaurantName = trimToNull(restaurantName);
	}

	public String getTagline() {
		return tagline;
	}

	public void setTagline(String tagline) {
		this.tagline = trimToNull(tagline);
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = trimToNull(description);
	}

	public String getRestaurantPhone() {
		return restaurantPhone;
	}

	public void setRestaurantPhone(String restaurantPhone) {
		this.restaurantPhone = User.normalizePhone(restaurantPhone);
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address == null ? null : address.strip();
	}

	public String getArea() {
		return area;
	}

	public void setArea(String area) {
		this.area = area == null ? null : area.strip();
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city == null ? null : city.strip();
	}

	public String getPincode() {
		return pincode;
	}

	public void setPincode(String pincode) {
		this.pincode = pincode == null ? null : trimToNull(pincode.replaceAll("\\s", ""));
	}

	public List<Long> getCuisineIds() {
		return cuisineIds;
	}

	public void setCuisineIds(List<Long> cuisineIds) {
		this.cuisineIds = cuisineIds == null ? new ArrayList<>() : new ArrayList<>(cuisineIds);
	}

	public boolean isPureVeg() {
		return pureVeg;
	}

	public void setPureVeg(boolean pureVeg) {
		this.pureVeg = pureVeg;
	}

	public MultipartFile getLogo() {
		return logo;
	}

	public void setLogo(MultipartFile logo) {
		this.logo = logo;
	}

	public boolean isRemoveLogo() {
		return removeLogo;
	}

	public void setRemoveLogo(boolean removeLogo) {
		this.removeLogo = removeLogo;
	}

	public MultipartFile getCover() {
		return cover;
	}

	public void setCover(MultipartFile cover) {
		this.cover = cover;
	}

	public boolean isRemoveCover() {
		return removeCover;
	}

	public void setRemoveCover(boolean removeCover) {
		this.removeCover = removeCover;
	}

	public LocalTime getOpensAt() {
		return opensAt;
	}

	public void setOpensAt(LocalTime opensAt) {
		this.opensAt = opensAt;
	}

	public LocalTime getClosesAt() {
		return closesAt;
	}

	public void setClosesAt(LocalTime closesAt) {
		this.closesAt = closesAt;
	}

	public int getAvgPrepMinutes() {
		return avgPrepMinutes;
	}

	public void setAvgPrepMinutes(int avgPrepMinutes) {
		this.avgPrepMinutes = avgPrepMinutes;
	}

	public BigDecimal getCostForTwo() {
		return costForTwo;
	}

	public void setCostForTwo(BigDecimal costForTwo) {
		this.costForTwo = costForTwo;
	}

	public BigDecimal getMinimumOrder() {
		return minimumOrder;
	}

	public void setMinimumOrder(BigDecimal minimumOrder) {
		this.minimumOrder = minimumOrder;
	}

	public BigDecimal getDeliveryFee() {
		return deliveryFee;
	}

	public void setDeliveryFee(BigDecimal deliveryFee) {
		this.deliveryFee = deliveryFee;
	}

}
