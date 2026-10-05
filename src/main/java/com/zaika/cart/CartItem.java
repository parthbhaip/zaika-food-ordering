package com.zaika.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.zaika.catalog.MenuItem;
import com.zaika.common.BaseEntity;

/** One dish in a saved cart: quantity 1-20 and the price the customer last saw. */
@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "cart_id", nullable = false, updatable = false)
	private Cart cart;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "menu_item_id", nullable = false, updatable = false)
	private MenuItem menuItem;

	@Min(1)
	@Max(CartService.MAX_QUANTITY)
	@Column(nullable = false)
	private int quantity;

	@Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal unitPrice;

	protected CartItem() {
	}

	CartItem(Cart cart, MenuItem menuItem, int quantity) {
		this.cart = Objects.requireNonNull(cart, "cart");
		this.menuItem = Objects.requireNonNull(menuItem, "menuItem");
		this.quantity = quantity;
		setUnitPrice(menuItem.getPrice());
	}

	public Cart getCart() {
		return cart;
	}

	public MenuItem getMenuItem() {
		return menuItem;
	}

	public int getQuantity() {
		return quantity;
	}

	void setQuantity(int quantity) {
		this.quantity = quantity;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	void setUnitPrice(BigDecimal unitPrice) {
		this.unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
	}

}
