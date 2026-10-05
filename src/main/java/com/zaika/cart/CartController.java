package com.zaika.cart;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.catalog.MenuItem;
import com.zaika.common.ForbiddenException;

/**
 * The cart for guests and customers, as plain forms (Post/Redirect/Get). {@code POST /cart/items/{id}} sets a dish's
 * quantity (the restaurant page's ADD and steppers, the cart page's steppers with {@code from=cart}); a dish from
 * another restaurant leads to {@code /cart/replace/{id}}, which asks "Start a new cart?". Staff accounts have no
 * cart (403).
 */
@Controller
class CartController {

	private final CartService carts;

	CartController(CartService carts) {
		this.carts = carts;
	}

	@GetMapping("/cart")
	String cart(HttpServletRequest request, Model model) {
		model.addAttribute("cart", carts.view(owner(request, false)));
		return "cart/cart";
	}

	@PostMapping("/cart/items/{itemId}")
	String setQuantity(@PathVariable long itemId, @RequestParam int quantity,
			@RequestParam(defaultValue = "false") boolean startNew, @RequestParam(required = false) String from,
			HttpServletRequest request, RedirectAttributes redirect) {
		if (quantity < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Negative quantity");
		}
		CartChange change = carts.setQuantity(owner(request, true), itemId, quantity, startNew);
		MenuItem item = change.item();
		switch (change.outcome()) {
			case OTHER_RESTAURANT -> {
				return "redirect:/cart/replace/" + itemId + "?quantity=" + Math.min(quantity, CartService.MAX_QUANTITY);
			}
			case ADDED -> redirect.addFlashAttribute("flashSuccess", "Added " + item.getName() + " to your cart.");
			case REMOVED -> redirect.addFlashAttribute("flashInfo", "Removed " + item.getName() + " from your cart.");
			case SOLD_OUT -> redirect.addFlashAttribute("flashError", item.getName() + " just sold out.");
			case CLOSED -> redirect.addFlashAttribute("flashError",
					item.getRestaurant().getName() + " is not taking orders right now.");
			case UPDATED, UNCHANGED -> {
			}
		}
		if (change.capped()) {
			redirect.addFlashAttribute("flashInfo",
					"You can order up to " + CartService.MAX_QUANTITY + " of a dish at a time.");
		}
		return "cart".equals(from) ? "redirect:/cart"
				: "redirect:/r/" + item.getRestaurant().getSlug() + "#dish-" + item.getId();
	}

	/** "Start a new cart?" Without a clash any more (cart emptied meanwhile, or a refresh), go to the cart. */
	@GetMapping("/cart/replace/{itemId}")
	String replace(@PathVariable long itemId, @RequestParam(defaultValue = "1") int quantity,
			HttpServletRequest request, Model model) {
		return carts.replacePrompt(owner(request, false), itemId).map(prompt -> {
			model.addAttribute("prompt", prompt);
			model.addAttribute("quantity", Math.clamp(quantity, 1, CartService.MAX_QUANTITY));
			return "cart/replace";
		}).orElse("redirect:/cart");
	}

	private static CartOwner owner(HttpServletRequest request, boolean create) {
		CartOwner owner = CartOwner.of(request, create);
		if (owner == null) {
			throw new ForbiddenException("Only customers and guests have a cart");
		}
		return owner;
	}

}
