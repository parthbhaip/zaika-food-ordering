package com.zaika.cart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.zaika.catalog.MenuItem;
import com.zaika.common.BaseEntity;
import com.zaika.user.User;

/** A signed-in customer's saved cart (one per customer). Load it through {@link CartRepository#findByUserId}. */
@Entity
@Table(name = "carts")
public class Cart extends BaseEntity implements CartContents {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false, unique = true)
	private User user;

	@OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<CartItem> items = new ArrayList<>();

	@Column(length = CartService.MAX_INSTRUCTIONS)
	private String instructions;

	protected Cart() {
	}

	Cart(User user) {
		this.user = Objects.requireNonNull(user, "user");
	}

	public User getUser() {
		return user;
	}

	public List<CartItem> getItems() {
		return items;
	}

	@Override
	public Long restaurantId() {
		return items.isEmpty() ? null : items.get(0).getMenuItem().getRestaurant().getId();
	}

	@Override
	public Map<Long, Integer> quantities() {
		Map<Long, Integer> quantities = new LinkedHashMap<>();
		items.forEach(item -> quantities.put(item.getMenuItem().getId(), item.getQuantity()));
		return quantities;
	}

	@Override
	public BigDecimal seenPrice(long itemId) {
		return find(itemId).map(CartItem::getUnitPrice).orElse(null);
	}

	@Override
	public void put(MenuItem menuItem, int quantity) {
		Optional<CartItem> line = find(menuItem.getId());
		if (line.isPresent()) {
			line.get().setQuantity(quantity);
			line.get().setUnitPrice(menuItem.getPrice());
		}
		else {
			items.add(new CartItem(this, menuItem, quantity));
		}
	}

	@Override
	public void markSeen(long itemId, BigDecimal price) {
		find(itemId).ifPresent(line -> line.setUnitPrice(price));
	}

	@Override
	public String instructions() {
		return instructions;
	}

	@Override
	public void setInstructions(String instructions) {
		this.instructions = instructions;
	}

	@Override
	public void remove(long itemId) {
		items.removeIf(line -> line.getMenuItem().getId() == itemId);
		if (items.isEmpty()) {
			instructions = null;
		}
	}

	@Override
	public void clear() {
		items.clear();
		instructions = null;
	}

	private Optional<CartItem> find(long itemId) {
		return items.stream().filter(line -> line.getMenuItem().getId() == itemId).findFirst();
	}

}
