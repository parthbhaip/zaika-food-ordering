package com.zaika.cart;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.cart.CartChange.Outcome;
import com.zaika.catalog.MenuItem;
import com.zaika.common.ForbiddenException;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponService.CartCoupon;
import com.zaika.coupon.CouponService.CouponResult;

/**
 * The cart for guests and customers. Every action is a plain form (Post/Redirect/Get with a flash toast); htmx
 * requests ({@code HX-Request}) get fragments of {@code cart/htmx} instead, with the header count, the phone cart bar
 * and the toast out of band ({@link CartModelInterceptor} supplies the count and bar after the change).
 * {@code POST /cart/items/{id}} sets a dish's quantity (the restaurant page's ADD and steppers, the cart page's
 * steppers with {@code from=cart}); a dish from another restaurant leads to {@code /cart/replace/{id}}, which asks
 * "Start a new cart?". Coupons: {@code POST /cart/coupon} ({@code code}) and {@code POST /cart/coupon/remove}, for
 * signed-in customers; guests are sent to sign in through {@code GET /cart/sign-in}, which comes back here. Staff
 * accounts have no cart (403).
 */
@Controller
class CartController {

	private final CartService carts;

	private final PricingService pricing;

	private final CouponService coupons;

	CartController(CartService carts, PricingService pricing, CouponService coupons) {
		this.carts = carts;
		this.pricing = pricing;
		this.coupons = coupons;
	}

	@GetMapping("/cart")
	String cart(HttpServletRequest request, Model model) {
		CartOwner owner = owner(request, false);
		CartView cart = carts.view(owner);
		showCart(owner, cart, model);
		model.addAttribute("instructionsForm", new InstructionsForm(cart.instructions()));
		return "cart/cart";
	}

	@PostMapping("/cart/items/{itemId}")
	String setQuantity(@PathVariable long itemId, @RequestParam int quantity,
			@RequestParam(defaultValue = "false") boolean startNew, @RequestParam(required = false) String from,
			HttpServletRequest request, HttpServletResponse response, Model model, RedirectAttributes redirect) {
		if (quantity < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Negative quantity");
		}
		CartOwner owner = owner(request, true);
		CartChange change = carts.setQuantity(owner, itemId, quantity, startNew);
		MenuItem item = change.item();
		boolean htmx = isHtmx(request);
		boolean fromCart = "cart".equals(from);
		if (change.outcome() == Outcome.OTHER_RESTAURANT) {
			String prompt = "/cart/replace/" + itemId + "?quantity=" + Math.min(quantity, CartService.MAX_QUANTITY);
			if (!htmx) {
				return "redirect:" + prompt;
			}
			response.setHeader("HX-Redirect", prompt);
			return "cart/htmx :: nothing";
		}
		List<Toast> toasts = toasts(change);
		if (!htmx) {
			toasts.forEach(toast -> redirect.addFlashAttribute(toast.flashAttribute(), toast.message()));
			return fromCart ? "redirect:/cart"
					: "redirect:/r/" + item.getRestaurant().getSlug() + "#dish-" + item.getId();
		}
		model.addAttribute("toasts", toasts);
		if (fromCart) {
			CartView cart = carts.view(owner);
			showCart(owner, cart, model);
			model.addAttribute("focusCart", change.outcome() == Outcome.REMOVED);
			if (cart.isEmpty()) { // the whole page body turns into the empty state
				response.setHeader("HX-Retarget", "#cart-body");
				response.setHeader("HX-Reswap", "outerHTML");
				return "cart/htmx :: cart-body";
			}
			return "cart/htmx :: cart-update";
		}
		model.addAttribute("item", item);
		model.addAttribute("canOrder", carts.isTakingOrders(item.getRestaurant()));
		model.addAttribute("quantity", change.quantity());
		model.addAttribute("focusAdd", change.quantity() == 0);
		return "cart/htmx :: dish";
	}

	@PostMapping("/cart/instructions")
	String saveInstructions(@Valid @ModelAttribute("instructionsForm") InstructionsForm form, BindingResult errors,
			HttpServletRequest request, HttpServletResponse response, Model model, RedirectAttributes redirect) {
		CartOwner owner = owner(request, false);
		boolean htmx = isHtmx(request);
		if (errors.hasErrors()) {
			if (htmx) {
				model.addAttribute("toasts", List.of());
				return "cart/htmx :: note";
			}
			showCart(owner, carts.view(owner), model);
			return "cart/cart";
		}
		if (!carts.saveInstructions(owner, form.getInstructions())) { // the cart was emptied in another tab
			if (htmx) {
				response.setHeader("HX-Redirect", "/cart");
				return "cart/htmx :: nothing";
			}
			return "redirect:/cart";
		}
		String message = form.getInstructions() == null || form.getInstructions().isEmpty()
				? "Note removed." : "Note saved. The kitchen will see it with your order.";
		if (!htmx) {
			redirect.addFlashAttribute("flashSuccess", message);
			return "redirect:/cart";
		}
		model.addAttribute("toasts", List.of(new Toast("success", message)));
		return "cart/htmx :: note";
	}

	/**
	 * Applies a coupon. Works: a toast and the bill with the discount. Does not: the reason under the code field,
	 * with the code kept (flash attributes {@code couponError} / {@code couponCode} without JavaScript).
	 */
	@PostMapping("/cart/coupon")
	String applyCoupon(@RequestParam(defaultValue = "") String code, HttpServletRequest request,
			HttpServletResponse response, Model model, RedirectAttributes redirect) {
		if (!(owner(request, false) instanceof CartOwner.Customer customer)) {
			return toSignIn(request, response);
		}
		CouponResult result = coupons.apply(customer.userId(), code);
		List<Toast> toasts = result.applied() ? List.of(new Toast("success", result.message())) : List.of();
		if (!isHtmx(request)) {
			if (result.applied()) {
				redirect.addFlashAttribute("flashSuccess", result.message());
			}
			else {
				redirect.addFlashAttribute("couponError", result.message());
				redirect.addFlashAttribute("couponCode", code.strip());
			}
			return "redirect:/cart";
		}
		if (!result.applied()) {
			model.addAttribute("couponError", result.message());
			model.addAttribute("couponCode", code.strip());
		}
		model.addAttribute("couponFocus", result.applied() ? "remove" : "code");
		return couponUpdate(customer, toasts, request, response, model);
	}

	@PostMapping("/cart/coupon/remove")
	String removeCoupon(HttpServletRequest request, HttpServletResponse response, Model model,
			RedirectAttributes redirect) {
		if (!(owner(request, false) instanceof CartOwner.Customer customer)) {
			return toSignIn(request, response);
		}
		coupons.remove(customer.userId());
		String message = "Coupon removed.";
		if (!isHtmx(request)) {
			redirect.addFlashAttribute("flashInfo", message);
			return "redirect:/cart";
		}
		model.addAttribute("couponFocus", "code");
		return couponUpdate(customer, List.of(new Toast("info", message)), request, response, model);
	}

	/**
	 * Guests reach this through sign-in (it needs an account, see SecurityConfig), so they come back to their cart,
	 * now merged into the account. Staff have no cart and go home.
	 */
	@GetMapping("/cart/sign-in")
	String signInForCart(HttpServletRequest request) {
		return CartOwner.of(request, false) == null ? "redirect:/" : "redirect:/cart";
	}

	/** The bill box after a coupon change (htmx); the whole page if the cart was emptied meanwhile. */
	private String couponUpdate(CartOwner owner, List<Toast> toasts, HttpServletRequest request,
			HttpServletResponse response, Model model) {
		CartView cart = carts.view(owner);
		if (cart.isEmpty()) {
			response.setHeader("HX-Redirect", "/cart");
			return "cart/htmx :: nothing";
		}
		showCart(owner, cart, model);
		model.addAttribute("toasts", toasts);
		return "cart/htmx :: side-update";
	}

	private static String toSignIn(HttpServletRequest request, HttpServletResponse response) {
		if (isHtmx(request)) {
			response.setHeader("HX-Redirect", "/cart/sign-in");
			return "cart/htmx :: nothing";
		}
		return "redirect:/cart/sign-in";
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

	/** The cart page's model: the re-checked cart, its coupon box, its bill and the free-delivery nudge. */
	private void showCart(CartOwner owner, CartView cart, Model model) {
		CartCoupon coupon = coupons.forCart(owner, cart);
		model.addAttribute("cart", cart);
		model.addAttribute("coupon", coupon);
		model.addAttribute("bill", pricing.price(cart, coupon.discount()));
		model.addAttribute("freeDeliveryGap", pricing.freeDeliveryGap(cart));
		model.addAttribute("gstPercent", pricing.gstPercent());
		model.addAttribute("hideCartBar", true); // the page itself has the checkout button
	}

	private static List<Toast> toasts(CartChange change) {
		MenuItem item = change.item();
		List<Toast> toasts = new ArrayList<>();
		switch (change.outcome()) {
			case ADDED -> toasts.add(new Toast("success", "Added " + item.getName() + " to your cart."));
			case REMOVED -> toasts.add(new Toast("info", "Removed " + item.getName() + " from your cart."));
			case SOLD_OUT -> toasts.add(new Toast("error", item.getName() + " just sold out."));
			case CLOSED -> toasts.add(new Toast("error", item.getRestaurant().getName() + " is not taking orders right now."));
			case UPDATED, UNCHANGED, OTHER_RESTAURANT -> {
			}
		}
		if (change.capped()) {
			toasts.add(new Toast("info", "You can order up to " + CartService.MAX_QUANTITY + " of a dish at a time."));
		}
		return toasts;
	}

	private static boolean isHtmx(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"));
	}

	private static CartOwner owner(HttpServletRequest request, boolean create) {
		CartOwner owner = CartOwner.of(request, create);
		if (owner == null) {
			throw new ForbiddenException("Only customers and guests have a cart");
		}
		return owner;
	}

}
