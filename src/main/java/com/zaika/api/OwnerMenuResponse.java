package com.zaika.api;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.zaika.catalog.MenuItem;
import com.zaika.owner.MenuManagementService.Menu;

/**
 * The owner's menu: live categories in menu order, each with its live dishes (sold-out ones included, archived ones
 * never). Switch a dish with {@code PUT /api/v1/owner/menu/items/{id}/availability}.
 */
public record OwnerMenuResponse(List<Section> sections, long dishCount, long soldOutCount) {

	static OwnerMenuResponse of(Menu menu) {
		return new OwnerMenuResponse(menu.sections().stream()
			.map(s -> new Section(s.category().getId(), s.category().getName(), s.items().stream().map(Dish::of).toList()))
			.toList(), menu.dishCount(), menu.soldOutCount());
	}

	@Schema(name = "OwnerMenuSection")
	public record Section(long categoryId, String name, List<Dish> dishes) {
	}

	/** A dish; {@code available} false = sold out (shown greyed out to customers, cannot be added to a cart). */
	@Schema(name = "OwnerMenuDish")
	public record Dish(long id, String name, BigDecimal price, boolean veg, boolean bestseller, boolean available) {

		static Dish of(MenuItem item) {
			return new Dish(item.getId(), item.getName(), item.getPrice(), item.isVeg(), item.isBestseller(),
					item.isAvailable());
		}

	}

}
