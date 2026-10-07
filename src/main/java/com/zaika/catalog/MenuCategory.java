package com.zaika.catalog;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.zaika.common.BaseEntity;

/** A section of a restaurant's menu ("Starters", "Thalis"), shown in ascending sort order unless archived. */
@Entity
@Table(name = "menu_categories")
public class MenuCategory extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
	private Restaurant restaurant;

	@NotBlank
	@Size(max = 60)
	@Column(nullable = false, length = 60)
	private String name;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	/** Deleted by the owner while archived dishes still point at it: hidden everywhere, kept for those dishes. */
	@Column(nullable = false)
	private boolean archived;

	protected MenuCategory() {
	}

	public MenuCategory(Restaurant restaurant, String name, int sortOrder) {
		this.restaurant = Objects.requireNonNull(restaurant, "restaurant");
		this.name = name;
		this.sortOrder = sortOrder;
	}

	public Restaurant getRestaurant() {
		return restaurant;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public boolean isArchived() {
		return archived;
	}

	public void setArchived(boolean archived) {
		this.archived = archived;
	}

}
