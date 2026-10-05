package com.zaika.cart;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.cart.CartChange.Outcome;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Cart rules, for a signed-in customer's saved cart and a guest's session cart alike: dishes from one restaurant
 * at a time, 1 to {@link #MAX_QUANTITY} of each, only orderable dishes from approved restaurants that are taking
 * orders can be added, and every look at the cart re-checks each dish's availability and price. Customers only
 * ever reach their own cart: it is looked up by their account id, never by a cart id from the request.
 */
@Service
@Transactional
public class CartService {

	public static final int MAX_QUANTITY = 20;

	private final CartRepository carts;

	private final MenuItemRepository items;

	private final RestaurantRepository restaurants;

	private final UserRepository users;

	private final Formatters fmt;

	private final Clock clock;

	CartService(CartRepository carts, MenuItemRepository items, RestaurantRepository restaurants,
			UserRepository users, Formatters fmt, Clock clock) {
		this.carts = carts;
		this.items = items;
		this.restaurants = restaurants;
		this.users = users;
		this.fmt = fmt;
		this.clock = clock;
	}

	/**
	 * Sets how many of a dish are in the cart; 0 removes it, more than {@link #MAX_QUANTITY} is capped. Adding
	 * needs the dish in stock and the restaurant taking orders; lowering a quantity always works. A dish from
	 * another restaurant than the cart's is only added with {@code startNew}, which empties the cart first.
	 *
	 * @throws NotFoundException if the dish does not exist, is archived, or its restaurant is not approved
	 */
	public CartChange setQuantity(CartOwner owner, long itemId, int quantity, boolean startNew) {
		if (quantity < 0) {
			throw new IllegalArgumentException("Quantity must not be negative");
		}
		MenuItem item = visibleItem(itemId);
		CartContents cart = loadForUpdate(owner, true);
		int current = cart.quantities().getOrDefault(itemId, 0);
		int target = Math.min(quantity, MAX_QUANTITY);
		boolean capped = quantity > MAX_QUANTITY;
		if (target > current) {
			if (!item.isAvailable()) {
				return new CartChange(Outcome.SOLD_OUT, item, current, false);
			}
			if (!takingOrders(item.getRestaurant())) {
				return new CartChange(Outcome.CLOSED, item, current, false);
			}
			Long cartRestaurant = cart.restaurantId();
			if (cartRestaurant != null && !cartRestaurant.equals(item.getRestaurant().getId())) {
				if (!startNew) {
					return new CartChange(Outcome.OTHER_RESTAURANT, item, 0, false);
				}
				cart.clear();
				current = 0;
			}
		}
		if (target == current) {
			return new CartChange(Outcome.UNCHANGED, item, current, capped);
		}
		if (target == 0) {
			cart.remove(itemId);
			return new CartChange(Outcome.REMOVED, item, 0, false);
		}
		cart.put(item, target);
		return new CartChange(current == 0 ? Outcome.ADDED : Outcome.UPDATED, item, target, capped);
	}

	/**
	 * The cart after re-checking it against the menu: dishes that were archived, deleted or sold out are taken out,
	 * a restaurant that is no longer approved empties the cart, and new prices replace the remembered ones. Each
	 * change comes back as a notice, once.
	 */
	public CartView view(CartOwner owner) {
		CartContents cart = loadForUpdate(owner, false);
		Map<Long, Integer> quantities = cart.quantities();
		if (quantities.isEmpty()) {
			return CartView.empty(List.of());
		}
		Map<Long, MenuItem> menu = items.findWithRestaurantByIdIn(quantities.keySet()).stream()
			.collect(Collectors.toMap(MenuItem::getId, Function.identity()));
		List<String> notices = new ArrayList<>();
		List<CartView.Line> lines = new ArrayList<>();
		Restaurant restaurant = null;
		for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
			long id = entry.getKey();
			MenuItem item = menu.get(id);
			if (item != null && item.getRestaurant().getStatus() != RestaurantStatus.APPROVED) {
				cart.clear();
				return CartView.empty(List.of(item.getRestaurant().getName()
						+ " is not taking orders on Zaika right now, so we emptied your cart."));
			}
			if (item == null || item.isArchived()) {
				cart.remove(id);
				notices.add(item == null ? "A dish in your cart was taken off the menu, so we removed it."
						: item.getName() + " was taken off the menu, so we removed it from your cart.");
				continue;
			}
			if (!item.isAvailable()) {
				cart.remove(id);
				notices.add(item.getName() + " just sold out, so we removed it from your cart.");
				continue;
			}
			BigDecimal seen = cart.seenPrice(id);
			if (seen != null && seen.compareTo(item.getPrice()) != 0) {
				notices.add(item.getName() + " now costs " + fmt.price(item.getPrice()) + " (was " + fmt.price(seen)
						+ ").");
				cart.markSeen(id, item.getPrice());
			}
			restaurant = item.getRestaurant();
			int quantity = entry.getValue();
			lines.add(new CartView.Line(item, quantity, item.getPrice().multiply(BigDecimal.valueOf(quantity))));
		}
		if (restaurant == null) {
			return CartView.empty(notices);
		}
		BigDecimal itemTotal = lines.stream().map(CartView.Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
			.setScale(2);
		int count = lines.stream().mapToInt(CartView.Line::quantity).sum();
		boolean open = takingOrders(restaurant);
		LocalTime opensAt = open || !restaurant.isAcceptingOrders() ? null : restaurant.getOpensAt();
		return new CartView(restaurant, open, opensAt, List.copyOf(lines), itemTotal, count, List.copyOf(notices));
	}

	/** Menu item id to quantity, for the steppers on a restaurant page. No re-check. */
	@Transactional(readOnly = true)
	public Map<Long, Integer> quantities(CartOwner owner) {
		return load(owner).quantities();
	}

	/** Dishes in the cart, summing quantities, for the header badge. No re-check. */
	@Transactional(readOnly = true)
	public int count(CartOwner owner) {
		return switch (owner) {
			case CartOwner.Customer customer -> Math.toIntExact(carts.countItems(customer.userId()));
			case CartOwner.Guest guest -> guest.cart().quantities().values().stream().mapToInt(Integer::intValue).sum();
		};
	}

	/**
	 * What to ask before adding {@code itemId}: present only while the cart holds another restaurant's dishes.
	 *
	 * @throws NotFoundException like {@link #setQuantity}
	 */
	@Transactional(readOnly = true)
	public Optional<ReplacePrompt> replacePrompt(CartOwner owner, long itemId) {
		MenuItem item = visibleItem(itemId);
		Long current = load(owner).restaurantId();
		if (current == null || current.equals(item.getRestaurant().getId())) {
			return Optional.empty();
		}
		return restaurants.findById(current).map(restaurant -> new ReplacePrompt(item, restaurant.getName()));
	}

	public void clear(CartOwner owner) {
		loadForUpdate(owner, false).clear();
	}

	/**
	 * Moves a guest's cart into the customer's saved cart when they sign in. Same restaurant: quantities are added
	 * up (capped at {@link #MAX_QUANTITY}). Another restaurant: the guest's picks win, being the newer intent, and
	 * the returned notice says which cart was cleared. Otherwise returns {@code null}.
	 */
	public String merge(GuestCart guest, long userId) {
		if (guest == null || guest.isEmpty()) {
			return null;
		}
		CartContents saved = loadForUpdate(new CartOwner.Customer(userId), true);
		String notice = null;
		Long savedRestaurant = saved.restaurantId();
		if (savedRestaurant != null && !savedRestaurant.equals(guest.restaurantId())) {
			String earlier = restaurants.findById(savedRestaurant).map(Restaurant::getName).orElse("another kitchen");
			saved.clear();
			notice = "Your cart now has the dishes you just picked. We cleared your earlier cart from " + earlier + ".";
		}
		Map<Long, Integer> mine = saved.quantities();
		Map<Long, MenuItem> menu = items.findWithRestaurantByIdIn(guest.quantities().keySet()).stream()
			.collect(Collectors.toMap(MenuItem::getId, Function.identity()));
		guest.quantities().forEach((id, quantity) -> {
			MenuItem item = menu.get(id);
			if (item != null) { // deleted from the menu meanwhile: nothing left to keep
				saved.put(item, Math.min(MAX_QUANTITY, mine.getOrDefault(id, 0) + quantity));
				saved.markSeen(id, guest.seenPrice(id)); // a price change since the guest added it still shows
			}
		});
		return notice;
	}

	private MenuItem visibleItem(long itemId) {
		return items.findWithRestaurantById(itemId)
			.filter(item -> !item.isArchived() && item.getRestaurant().getStatus() == RestaurantStatus.APPROVED)
			.orElseThrow(() -> new NotFoundException("Menu item " + itemId));
	}

	private boolean takingOrders(Restaurant restaurant) {
		return restaurant.isAcceptingOrders() && restaurant.isOpenNow(clock);
	}

	private CartContents load(CartOwner owner) {
		return switch (owner) {
			case CartOwner.Guest guest -> guest.cart();
			case CartOwner.Customer customer -> carts.findByUserId(customer.userId())
				.<CartContents>map(Function.identity())
				.orElseGet(GuestCart::new);
		};
	}

	/**
	 * The cart, with the customer's account row locked so parallel requests (a double-tapped ADD) change it one
	 * after the other. Without a saved cart: a new one when {@code create}, else an empty stand-in.
	 */
	private CartContents loadForUpdate(CartOwner owner, boolean create) {
		return switch (owner) {
			case CartOwner.Guest guest -> guest.cart();
			case CartOwner.Customer customer -> {
				User user = users.findForUpdate(customer.userId())
					.orElseThrow(() -> new NotFoundException("User " + customer.userId()));
				Optional<Cart> cart = carts.findByUserId(customer.userId());
				if (cart.isPresent()) {
					yield cart.get();
				}
				yield create ? carts.save(new Cart(user)) : new GuestCart();
			}
		};
	}

	/** "Start a new cart?": the dish the customer wants and the restaurant their cart is from now. */
	public record ReplacePrompt(MenuItem item, String currentRestaurant) {
	}

}
