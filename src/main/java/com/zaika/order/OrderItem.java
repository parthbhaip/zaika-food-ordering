package com.zaika.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.zaika.catalog.MenuItem;
import com.zaika.common.BaseEntity;

/**
 * One dish line of an order: name, price and veg mark as they were at checkout. {@code menuItem} links back to the
 * dish (for "order again" and so a dish that was ever ordered is archived rather than deleted).
 */
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, updatable = false)
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_item_id", updatable = false)
	private MenuItem menuItem;

	@Column(nullable = false, length = 80, updatable = false)
	private String name;

	@Column(name = "unit_price", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal unitPrice;

	@Column(nullable = false, updatable = false)
	private int quantity;

	@Column(nullable = false, updatable = false)
	private boolean veg;

	protected OrderItem() {
	}

	OrderItem(Order order, MenuItem menuItem, int quantity) {
		this.order = Objects.requireNonNull(order, "order");
		this.menuItem = Objects.requireNonNull(menuItem, "menuItem");
		this.name = menuItem.getName();
		this.unitPrice = menuItem.getPrice().setScale(2, RoundingMode.HALF_UP);
		this.quantity = quantity;
		this.veg = menuItem.isVeg();
	}

	/** Unit price times quantity. */
	public BigDecimal getLineTotal() {
		return unitPrice.multiply(BigDecimal.valueOf(quantity));
	}

	public Order getOrder() {
		return order;
	}

	public MenuItem getMenuItem() {
		return menuItem;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public int getQuantity() {
		return quantity;
	}

	public boolean isVeg() {
		return veg;
	}

}
