package com.zaika.cart;

import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.zaika.security.CurrentUser;

/** Whose cart a request works on: a signed-in customer's saved cart, or a guest's session cart. */
public sealed interface CartOwner {

	record Customer(long userId) implements CartOwner {
	}

	record Guest(GuestCart cart) implements CartOwner {

		public Guest {
			Objects.requireNonNull(cart, "cart");
		}

	}

	/**
	 * The cart owner of the current request: a signed-in customer, or a guest. {@code null} for staff accounts
	 * (owners, riders, admins), who have no cart. A guest's cart is stored in the session only when {@code create}
	 * is set, so just looking at an empty cart never starts a session.
	 */
	static CartOwner of(HttpServletRequest request, boolean create) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth instanceof AnonymousAuthenticationToken) {
			HttpSession session = request.getSession(create);
			GuestCart cart = session == null ? null : (GuestCart) session.getAttribute(GuestCart.SESSION_KEY);
			if (cart == null) {
				cart = new GuestCart();
				if (create) {
					session.setAttribute(GuestCart.SESSION_KEY, cart);
				}
			}
			return new Guest(cart);
		}
		boolean customer = auth.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()));
		return customer && auth.getPrincipal() instanceof CurrentUser user ? new Customer(user.getId()) : null;
	}

}
