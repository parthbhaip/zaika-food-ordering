package com.zaika.api;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zaika.cart.CartChange;
import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.cart.CartView;
import com.zaika.cart.PricingService;
import com.zaika.config.AppProperties;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponService.CartCoupon;
import com.zaika.coupon.CouponService.CouponResult;
import com.zaika.security.CurrentUser;
import com.zaika.storage.ImageUrls;

/**
 * The signed-in customer's cart (CUSTOMER tokens only), the same saved cart the website shows: dishes from one
 * restaurant, 1 to {@link CartService#MAX_QUANTITY} of each, the note for the kitchen, one coupon and the bill. Every
 * change answers with the whole re-checked cart. Refusals by the rules (sold out, kitchen closed, another restaurant's
 * cart, empty cart) are 409 problems; an unknown or archived dish is a 404.
 */
@RestController
@RequestMapping("/api/v1/cart")
class CartApiController {

	private final CartService carts;

	private final CouponService coupons;

	private final PricingService pricing;

	private final ImageUrls images;

	private final String baseUrl;

	CartApiController(CartService carts, CouponService coupons, PricingService pricing, ImageUrls images,
			AppProperties app) {
		this.carts = carts;
		this.coupons = coupons;
		this.pricing = pricing;
		this.images = images;
		this.baseUrl = app.baseUrl();
	}

	@GetMapping
	CartResponse cart(@AuthenticationPrincipal CurrentUser me) {
		return show(owner(me));
	}

	/**
	 * Adds a dish or changes its quantity (0 removes it). A dish from another restaurant than the cart's is a 409
	 * unless {@code startNew} is true, which empties the cart first.
	 */
	@PutMapping("/items/{itemId}")
	CartResponse setQuantity(@AuthenticationPrincipal CurrentUser me, @PathVariable long itemId,
			@Valid @RequestBody QuantityRequest body) {
		CartOwner owner = owner(me);
		CartChange change = carts.setQuantity(owner, itemId, body.quantity(), body.startNew());
		String dish = change.item().getName();
		String restaurant = change.item().getRestaurant().getName();
		switch (change.outcome()) {
			case SOLD_OUT -> throw ApiExceptionHandler.conflict(dish + " just sold out.", Map.of("reason", "SOLD_OUT"));
			case CLOSED -> throw ApiExceptionHandler.conflict(restaurant + " is not taking orders right now.",
					Map.of("reason", "CLOSED"));
			case OTHER_RESTAURANT -> {
				String current = carts.summary(owner).restaurantName(); // the cart did not change
				throw ApiExceptionHandler.conflict("Your cart has dishes from " + current
						+ ". Send \"startNew\": true to empty it and start a new cart from " + restaurant + ".",
						Map.of("reason", "OTHER_RESTAURANT", "cartRestaurant", String.valueOf(current)));
			}
			case ADDED, UPDATED, REMOVED, UNCHANGED -> {
			}
		}
		return show(owner);
	}

	/** Takes a dish out of the cart (nothing happens if it was not there). */
	@DeleteMapping("/items/{itemId}")
	CartResponse remove(@AuthenticationPrincipal CurrentUser me, @PathVariable long itemId) {
		CartOwner owner = owner(me);
		carts.setQuantity(owner, itemId, 0, false);
		return show(owner);
	}

	/** Empties the cart: 204. */
	@DeleteMapping
	ResponseEntity<Void> clear(@AuthenticationPrincipal CurrentUser me) {
		carts.clear(owner(me));
		return ResponseEntity.noContent().build();
	}

	/** The note for the restaurant (blank removes it); 409 while the cart is empty. */
	@PutMapping("/note")
	CartResponse note(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody NoteRequest body) {
		CartOwner owner = owner(me);
		if (!carts.saveInstructions(owner, body.instructions())) {
			throw ApiExceptionHandler.conflict("Your cart is empty. Add a dish before writing a note for the kitchen.",
					Map.of("reason", "EMPTY_CART"));
		}
		return show(owner);
	}

	/** Applies a coupon (replacing any applied one); a code that does not work is a 400 with the reason. */
	@PostMapping("/coupon")
	CartResponse applyCoupon(@AuthenticationPrincipal CurrentUser me, @RequestBody CouponRequest body) {
		CouponResult result = coupons.apply(me.getId(), body.code());
		if (!result.applied()) {
			throw ApiExceptionHandler.invalidField("code", result.message());
		}
		return show(owner(me));
	}

	@DeleteMapping("/coupon")
	CartResponse removeCoupon(@AuthenticationPrincipal CurrentUser me) {
		coupons.remove(me.getId());
		return show(owner(me));
	}

	private CartResponse show(CartOwner owner) {
		CartView cart = carts.view(owner);
		CartCoupon coupon = coupons.forCart(owner, cart);
		return CartResponse.of(cart, coupon, pricing.price(cart, coupon.discount()), pricing.freeDeliveryGap(cart),
				images, baseUrl);
	}

	private static CartOwner owner(CurrentUser me) {
		return new CartOwner.Customer(me.getId());
	}

	/** How many of the dish the cart should hold: 0 (remove) to 20. */
	record QuantityRequest(
			@NotNull(message = "Send a quantity from 0 to " + CartService.MAX_QUANTITY + ".")
			@Min(value = 0, message = "Send a quantity from 0 to " + CartService.MAX_QUANTITY + ".")
			@Max(value = CartService.MAX_QUANTITY, message = "You can order up to " + CartService.MAX_QUANTITY
					+ " of a dish at a time.") Integer quantity,
			Boolean startNew) {

		/** Missing means false: add to the current cart. */
		QuantityRequest {
			startNew = Boolean.TRUE.equals(startNew);
		}

	}

	record NoteRequest(@Size(max = CartService.MAX_INSTRUCTIONS,
			message = "Keep the note to 250 characters or fewer.") String instructions) {

		/** Line breaks count once, as on the website. */
		NoteRequest {
			instructions = instructions == null ? null : instructions.replace("\r\n", "\n").strip();
		}

	}

	record CouponRequest(String code) {
	}

}
