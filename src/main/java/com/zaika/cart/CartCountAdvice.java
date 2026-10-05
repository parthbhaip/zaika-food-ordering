package com.zaika.cart;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Gives every page the header cart badge's {@code cartCount}: one count query for customers, none for guests. */
@ControllerAdvice
class CartCountAdvice {

	private final CartService carts;

	CartCountAdvice(CartService carts) {
		this.carts = carts;
	}

	@ModelAttribute("cartCount")
	int cartCount(HttpServletRequest request) {
		CartOwner owner = CartOwner.of(request, false);
		return owner == null ? 0 : carts.count(owner);
	}

}
