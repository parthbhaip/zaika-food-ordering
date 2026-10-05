package com.zaika.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.zaika.common.BaseEntity;

/**
 * A dish on a restaurant's menu. Unavailable items are shown as sold out; archived items (ever ordered, then removed
 * by the owner) are hidden everywhere but kept for history. The restaurant always equals the category's restaurant.
 */
@Entity
@Table(name = "menu_items")
public class MenuItem extends BaseEntity {

	public static final int MAX_SPICE_LEVEL = 3;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private MenuCategory category;

	@NotBlank
	@Size(max = 80)
	@Column(nullable = false, length = 80)
	private String name;

	@Size(max = 300)
	@Column(length = 300)
	private String description;

	@NotNull
	@DecimalMin(value = "0.00", inclusive = false)
	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	@Column(nullable = false)
	private boolean veg;

	@Column(nullable = false)
	private boolean bestseller;

	/** 0 = not spicy, 3 = very spicy. */
	@Min(0)
	@Max(MAX_SPICE_LEVEL)
	@Column(name = "spice_level", nullable = false)
	private int spiceLevel;

	@Column(nullable = false)
	private boolean available = true;

	@Column(nullable = false)
	private boolean archived;

	@Size(max = 255)
	@Column(name = "image_path")
	private String imagePath;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	protected MenuItem() {
	}

	/** A new item in {@code category} (and so in its restaurant): available, not archived, not spicy. */
	public MenuItem(MenuCategory category, String name, BigDecimal price, boolean veg) {
		this.category = Objects.requireNonNull(category, "category");
		this.restaurant = category.getRestaurant();
		this.name = name;
		setPrice(price);
		this.veg = veg;
	}

	/** Shown on the menu and orderable: available and not archived. */
	public boolean isOrderable() {
		return available && !archived;
	}

	public Restaurant getRestaurant() {
		return restaurant;
	}

	public MenuCategory getCategory() {
		return category;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
	}

	public boolean isVeg() {
		return veg;
	}

	public void setVeg(boolean veg) {
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

	public boolean isArchived() {
		return archived;
	}

	public void setArchived(boolean archived) {
		this.archived = archived;
	}

	public String getImagePath() {
		return imagePath;
	}

	public void setImagePath(String imagePath) {
		this.imagePath = imagePath;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

}
