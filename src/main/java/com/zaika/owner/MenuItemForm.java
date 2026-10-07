package com.zaika.owner;

import java.math.BigDecimal;
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

import org.springframework.web.multipart.MultipartFile;

import com.zaika.catalog.MenuItem;

/** Adding or editing a dish. {@code veg} has no default for a new dish: the owner must say which it is. */
public class MenuItemForm {

	/** Spice levels as offered in the form, index = level. */
	public static final List<String> SPICE_LABELS = List.of("Not spicy", "Mild", "Spicy", "Very spicy");

	@NotNull(message = "Choose a category.")
	private Long categoryId;

	@NotBlank(message = "Give the dish a name.")
	@Size(max = 80, message = "Keep the name under 80 characters.")
	@Pattern(regexp = ".*\\p{Alnum}.*", message = "Use letters or digits in the name.")
	private String name;

	@Size(max = 300, message = "Keep the description under 300 characters.")
	private String description;

	@NotNull(message = "Enter a price, like 180.")
	@DecimalMin(value = "1.00", message = "The price should be at least ₹1.")
	@DecimalMax(value = "10000.00", message = "The price can be at most ₹10,000.")
	@Digits(integer = 5, fraction = 2, message = "Use rupees and up to 2 decimals, like 180 or 179.50.")
	private BigDecimal price;

	@NotNull(message = "Choose veg or non-veg.")
	private Boolean veg;

	private boolean bestseller;

	@Min(value = 0, message = "Choose a spice level.")
	@Max(value = MenuItem.MAX_SPICE_LEVEL, message = "Choose a spice level.")
	private int spiceLevel;

	private boolean available = true;

	private MultipartFile image;

	/** Take the current photo off (ignored when a new one is attached). */
	private boolean removeImage;

	/** The form for this dish as it is now. */
	static MenuItemForm of(MenuItem item) {
		MenuItemForm form = new MenuItemForm();
		form.categoryId = item.getCategory().getId();
		form.name = item.getName();
		form.description = item.getDescription();
		form.price = item.getPrice();
		form.veg = item.isVeg();
		form.bestseller = item.isBestseller();
		form.spiceLevel = item.getSpiceLevel();
		form.available = item.isAvailable();
		return form;
	}

	/** A photo was attached. */
	public boolean hasImage() {
		return image != null && !image.isEmpty();
	}

	public Long getCategoryId() {
		return categoryId;
	}

	public void setCategoryId(Long categoryId) {
		this.categoryId = categoryId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = trimToNull(name);
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = trimToNull(description);
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public Boolean getVeg() {
		return veg;
	}

	public void setVeg(Boolean veg) {
		this.veg = veg;
	}

	public boolean isBestseller() {
		return bestseller;
	}

	public void setBestseller(boolean bestseller) {
		this.bestseller = bestseller;
	}

	public int getSpiceLevel() {
		return spiceLevel;
	}

	public void setSpiceLevel(int spiceLevel) {
		this.spiceLevel = spiceLevel;
	}

	public boolean isAvailable() {
		return available;
	}

	public void setAvailable(boolean available) {
		this.available = available;
	}

	public MultipartFile getImage() {
		return image;
	}

	public void setImage(MultipartFile image) {
		this.image = image;
	}

	public boolean isRemoveImage() {
		return removeImage;
	}

	public void setRemoveImage(boolean removeImage) {
		this.removeImage = removeImage;
	}

	private static String trimToNull(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

}
