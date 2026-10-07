package com.zaika.owner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.common.NotFoundException;
import com.zaika.order.OrderRepository;
import com.zaika.storage.ImageKind;
import com.zaika.storage.InvalidImageException;
import com.zaika.storage.StorageService;

/**
 * The owner's menu ({@code /owner/menu}): categories (add, rename, reorder, delete) and dishes (add, edit, move to
 * another category, sold out / back in stock, delete). Everything is looked up through the signed-in owner's own
 * restaurant, so another owner's category or dish id is simply not found (404).
 * <p>
 * A dish that was ever ordered is archived instead of deleted (past orders point at it); a category is archived
 * instead of deleted while archived dishes still point at it. Archived rows are hidden everywhere.
 */
@Service
public class MenuManagementService {

	private final RestaurantRepository restaurants;

	private final MenuCategoryRepository categories;

	private final MenuItemRepository items;

	private final OrderRepository orders;

	private final StorageService storage;

	MenuManagementService(RestaurantRepository restaurants, MenuCategoryRepository categories,
			MenuItemRepository items, OrderRepository orders, StorageService storage) {
		this.restaurants = restaurants;
		this.categories = categories;
		this.items = items;
		this.orders = orders;
		this.storage = storage;
	}

	// ------------------------------------------------------------------------------------------- reading

	/** The owner's restaurant and its live categories with their live dishes, in menu order. Three queries. */
	@Transactional(readOnly = true)
	public Menu menu(long ownerId) {
		Restaurant restaurant = restaurantOf(ownerId);
		Map<MenuCategory, List<MenuItem>> byCategory = new LinkedHashMap<>();
		for (MenuCategory category : liveCategories(restaurant)) {
			byCategory.put(category, new ArrayList<>());
		}
		for (MenuItem item : items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId())) {
			// same persistence context: the item's category is the instance loaded above (live dishes sit in live categories)
			byCategory.computeIfAbsent(item.getCategory(), c -> new ArrayList<>()).add(item);
		}
		List<Section> sections = byCategory.entrySet().stream()
			.map(e -> new Section(e.getKey(), List.copyOf(e.getValue())))
			.toList();
		return new Menu(restaurant, sections);
	}

	/** What the "Add a dish" page needs. */
	@Transactional(readOnly = true)
	public DishPage newDish(long ownerId) {
		Restaurant restaurant = restaurantOf(ownerId);
		return new DishPage(restaurant, liveCategories(restaurant), null);
	}

	/** What the "Edit dish" page needs; another restaurant's or an archived dish is a 404. */
	@Transactional(readOnly = true)
	public DishPage editDish(long ownerId, long itemId) {
		Restaurant restaurant = restaurantOf(ownerId);
		return new DishPage(restaurant, liveCategories(restaurant), dishOf(restaurant, itemId));
	}

	// ------------------------------------------------------------------------------------------- categories

	/** Adds a category at the end of the menu. */
	@Transactional
	public MenuCategory addCategory(long ownerId, String name) {
		Restaurant restaurant = restaurantOf(ownerId);
		checkNameFree(restaurant, name, null);
		return categories.save(new MenuCategory(restaurant, name, categories.maxSortOrder(restaurant.getId()) + 1));
	}

	@Transactional
	public MenuCategory renameCategory(long ownerId, long categoryId, String name) {
		Restaurant restaurant = restaurantOf(ownerId);
		MenuCategory category = categoryOf(restaurant, categoryId);
		checkNameFree(restaurant, name, category);
		category.setName(name);
		return category;
	}

	/**
	 * Moves the category one place up or down the menu and numbers the categories 1, 2, 3... again. At the top (or
	 * bottom) nothing changes.
	 */
	@Transactional
	public MenuCategory moveCategory(long ownerId, long categoryId, boolean up) {
		Restaurant restaurant = restaurantOf(ownerId);
		MenuCategory category = categoryOf(restaurant, categoryId);
		List<MenuCategory> order = new ArrayList<>(liveCategories(restaurant));
		int at = order.indexOf(category);
		int to = up ? at - 1 : at + 1;
		if (to >= 0 && to < order.size()) {
			order.set(at, order.get(to));
			order.set(to, category);
		}
		for (int i = 0; i < order.size(); i++) {
			order.get(i).setSortOrder(i + 1);
		}
		return category;
	}

	/**
	 * Deletes an empty category. One that still holds dishes is refused; one whose only dishes are archived is
	 * archived itself.
	 * @throws OwnerRuleException if dishes are still listed in it
	 */
	@Transactional
	public MenuCategory deleteCategory(long ownerId, long categoryId) {
		Restaurant restaurant = restaurantOf(ownerId);
		MenuCategory category = categoryOf(restaurant, categoryId);
		long dishes = items.countByCategoryIdAndArchivedFalse(category.getId());
		if (dishes > 0) {
			throw new OwnerRuleException(null, "Move or delete the " + (dishes == 1 ? "dish" : dishes + " dishes")
					+ " in “" + category.getName() + "” first, then delete the category.");
		}
		if (items.existsByCategoryId(category.getId())) {
			category.setArchived(true);
		}
		else {
			categories.delete(category);
		}
		return category;
	}

	// ------------------------------------------------------------------------------------------- dishes

	/**
	 * Adds a dish (and its photo, if one is attached) at the end of its category.
	 * @throws OwnerRuleException for a non-veg dish in a pure-veg kitchen or an unusable photo
	 */
	@Transactional
	public MenuItem addDish(long ownerId, MenuItemForm form) {
		Restaurant restaurant = restaurantOf(ownerId);
		MenuCategory category = checkDish(restaurant, form);
		MenuItem item = new MenuItem(category, form.getName(), form.getPrice(), form.getVeg());
		item.setSortOrder(items.maxSortOrder(restaurant.getId()) + 1);
		apply(item, form);
		item.setImagePath(form.hasImage() ? storePhoto(form) : null);
		return items.save(item);
	}

	/**
	 * Saves the dish's new details; a new category moves it to the end of that category. A new photo replaces the
	 * old one, which is deleted once the change is saved.
	 * @throws OwnerRuleException for a non-veg dish in a pure-veg kitchen or an unusable photo
	 */
	@Transactional
	public MenuItem updateDish(long ownerId, long itemId, MenuItemForm form) {
		Restaurant restaurant = restaurantOf(ownerId);
		MenuItem item = dishOf(restaurant, itemId);
		MenuCategory category = checkDish(restaurant, form);
		if (!category.equals(item.getCategory())) {
			item.moveTo(category);
			item.setSortOrder(items.maxSortOrder(restaurant.getId()) + 1);
		}
		item.setName(form.getName());
		item.setPrice(form.getPrice());
		item.setVeg(form.getVeg());
		apply(item, form);
		if (form.hasImage() || form.isRemoveImage()) {
			String old = item.getImagePath();
			item.setImagePath(form.hasImage() ? storePhoto(form) : null);
			storage.deleteAfterCommit(old);
		}
		return item;
	}

	/** Marks the dish sold out ({@code false}) or back in stock. */
	@Transactional
	public MenuItem setAvailable(long ownerId, long itemId, boolean available) {
		MenuItem item = dishOf(restaurantOf(ownerId), itemId);
		item.setAvailable(available);
		return item;
	}

	/**
	 * Takes the dish off the menu: a dish that was ever ordered is archived (past orders still show it), any other
	 * is deleted with its photo (and drops out of carts).
	 */
	@Transactional
	public Removed deleteDish(long ownerId, long itemId) {
		MenuItem item = dishOf(restaurantOf(ownerId), itemId);
		// ponytail: check-then-delete; an order placed in between makes the delete fail on the FK (a 500, nothing lost).
		// Lock the dish row against checkout if that ever shows up.
		if (orders.wasEverOrdered(item.getId())) {
			item.setArchived(true);
			return new Removed(item.getName(), true);
		}
		items.delete(item);
		storage.deleteAfterCommit(item.getImagePath());
		return new Removed(item.getName(), false);
	}

	// ------------------------------------------------------------------------------------------- helpers

	private Restaurant restaurantOf(long ownerId) {
		return restaurants.findByOwnerId(ownerId)
			.orElseThrow(() -> new NotFoundException("No restaurant for owner " + ownerId));
	}

	private List<MenuCategory> liveCategories(Restaurant restaurant) {
		return categories.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(restaurant.getId());
	}

	private MenuCategory categoryOf(Restaurant restaurant, long categoryId) {
		return categories.findByIdAndRestaurantIdAndArchivedFalse(categoryId, restaurant.getId())
			.orElseThrow(() -> new NotFoundException("No category " + categoryId + " at " + restaurant.getSlug()));
	}

	private MenuItem dishOf(Restaurant restaurant, long itemId) {
		return items.findByIdAndRestaurantIdAndArchivedFalse(itemId, restaurant.getId())
			.orElseThrow(() -> new NotFoundException("No dish " + itemId + " at " + restaurant.getSlug()));
	}

	private void checkNameFree(Restaurant restaurant, String name, MenuCategory self) {
		boolean taken = liveCategories(restaurant).stream()
			.anyMatch(c -> !c.equals(self) && c.getName().equalsIgnoreCase(name));
		if (taken) {
			throw new OwnerRuleException("name", "You already have a category called “" + name + "”.");
		}
	}

	/** The form's category (must be one of this restaurant's) after the menu rules are checked. */
	private MenuCategory checkDish(Restaurant restaurant, MenuItemForm form) {
		MenuCategory category = categories.findByIdAndRestaurantIdAndArchivedFalse(form.getCategoryId(),
				restaurant.getId())
			.orElseThrow(() -> new OwnerRuleException("categoryId", "Choose one of your categories."));
		if (restaurant.isPureVeg() && !form.getVeg()) {
			throw new OwnerRuleException("veg", restaurant.getName()
					+ " is listed as pure veg, so every dish must be veg.");
		}
		return category;
	}

	private static void apply(MenuItem item, MenuItemForm form) {
		item.setDescription(form.getDescription());
		item.setBestseller(form.isBestseller());
		item.setSpiceLevel(form.getSpiceLevel());
		item.setAvailable(form.isAvailable());
	}

	/** Stores the attached photo; if the transaction then rolls back, the file is removed again. */
	private String storePhoto(MenuItemForm form) {
		try {
			return storage.storeForTransaction(ImageKind.DISH, form.getImage());
		}
		catch (InvalidImageException ex) {
			throw new OwnerRuleException("image", ex.getMessage());
		}
	}

	// ------------------------------------------------------------------------------------------- results

	/** The owner's menu: live categories in order, each with its live dishes. */
	public record Menu(Restaurant restaurant, List<Section> sections) {

		public long dishCount() {
			return sections.stream().mapToLong(s -> s.items().size()).sum();
		}

		public long soldOutCount() {
			return sections.stream().mapToLong(Section::soldOutCount).sum();
		}

	}

	/** One category and its dishes. */
	public record Section(MenuCategory category, List<MenuItem> items) {

		public long soldOutCount() {
			return items.stream().filter(i -> !i.isAvailable()).count();
		}

	}

	/** The add / edit dish page: the restaurant, the categories to choose from, and the dish (null when adding). */
	public record DishPage(Restaurant restaurant, List<MenuCategory> categories, MenuItem item) {
	}

	/** A dish taken off the menu: archived (it was ordered before) or deleted. */
	public record Removed(String name, boolean archived) {
	}

}
