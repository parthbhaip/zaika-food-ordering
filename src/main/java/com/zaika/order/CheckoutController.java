package com.zaika.order;

import java.util.List;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.cart.CartOwner;
import com.zaika.cart.CartService;
import com.zaika.cart.CartView;
import com.zaika.cart.PriceBreakdown;
import com.zaika.cart.PricingService;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponService.CartCoupon;
import com.zaika.security.CurrentUser;
import com.zaika.user.Address;
import com.zaika.user.AddressService;

/**
 * {@code /checkout} for signed-in customers (SecurityConfig): pick a saved address or type a new one, a note for the
 * rider, cash or online payment, and the order summary with the bill. A plain form; {@link CheckoutService} re-checks
 * and prices everything again. Placed: cash orders go to their order page, online ones to the payment page. A cart
 * that cannot be checked out (empty, closed, below the minimum) sends the customer back to {@code /cart}.
 */
@Controller
@RequestMapping("/checkout")
class CheckoutController {

	static final String VIEW = "order/checkout";

	private final CheckoutService checkout;

	private final CartService carts;

	private final CouponService coupons;

	private final PricingService pricing;

	private final AddressService addresses;

	private final Validator validator;

	CheckoutController(CheckoutService checkout, CartService carts, CouponService coupons, PricingService pricing,
			AddressService addresses, Validator validator) {
		this.checkout = checkout;
		this.carts = carts;
		this.coupons = coupons;
		this.pricing = pricing;
		this.addresses = addresses;
		this.validator = validator;
	}

	@GetMapping
	String page(@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		CheckoutForm form = new CheckoutForm();
		form.setIdempotencyKey(UUID.randomUUID().toString());
		return show(me, form, model, redirect);
	}

	@PostMapping
	String place(@AuthenticationPrincipal CurrentUser me, @Valid @ModelAttribute("form") CheckoutForm form,
			BindingResult errors, Model model, RedirectAttributes redirect) {
		if (!form.hasValidKey()) { // only a tampered or truncated request lacks it
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing checkout key");
		}
		if (form.wantsNewAddress()) {
			for (ConstraintViolation<?> v : validator.validate(form.getNewAddress())) {
				errors.rejectValue("newAddress." + v.getPropertyPath(), "invalid", v.getMessage());
			}
		}
		if (errors.hasErrors()) {
			model.addAttribute("checkoutError", "Your order was not placed. Fix the fields marked below.");
			return show(me, form, model, redirect);
		}
		CheckoutService.Placement placement;
		try {
			placement = checkout.placeOrder(me.getId(), form);
		}
		catch (CheckoutException ex) {
			if (ex.isBackToCart()) {
				if (ex.getMessage() != null) {
					redirect.addFlashAttribute("flashError", ex.getMessage());
				}
				return "redirect:/cart";
			}
			model.addAttribute("checkoutError", ex.getMessage());
			return show(me, form, model, redirect);
		}
		Order order = placement.order();
		if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
			return "redirect:/pay/" + order.getCode();
		}
		if (!placement.repeat()) {
			redirect.addFlashAttribute(OrderController.PLACED, true);
		}
		redirect.addFlashAttribute(placement.repeat() ? "flashInfo" : "flashSuccess", placement.repeat()
				? "This order was already placed. Here it is." : "Order placed. " + order.getRestaurant().getName()
						+ " will confirm it shortly.");
		return "redirect:/orders/" + order.getCode();
	}

	/** The page for {@code form}, freshly priced; back to the cart when the cart cannot be checked out. */
	private String show(CurrentUser me, CheckoutForm form, Model model, RedirectAttributes redirect) {
		CartOwner owner = new CartOwner.Customer(me.getId());
		CartView cart = carts.view(owner);
		if (!cart.canCheckout()) {
			if (!cart.notices().isEmpty()) {
				redirect.addFlashAttribute("flashError", String.join(" ", cart.notices()));
			}
			return "redirect:/cart";
		}
		CartCoupon coupon = coupons.forCart(owner, cart);
		PriceBreakdown bill = pricing.price(cart, coupon.discount());
		List<Address> book = addresses.list(me.getId());
		boolean bookFull = book.size() >= AddressService.MAX_ADDRESSES;
		if (form.getAddressId() == null) {
			form.setAddressId(book.isEmpty() ? CheckoutForm.NEW_ADDRESS : book.get(0).getId()); // default first
		}
		form.setExpectedTotal(bill.grandTotal()); // what this page shows is what the order will cost
		model.addAttribute("form", form);
		model.addAttribute("cart", cart);
		model.addAttribute("coupon", coupon);
		model.addAttribute("bill", bill);
		model.addAttribute("gstPercent", pricing.gstPercent());
		model.addAttribute("addresses", book);
		model.addAttribute("addressBookFull", bookFull);
		model.addAttribute("hideCartBar", true);
		return VIEW;
	}

}
