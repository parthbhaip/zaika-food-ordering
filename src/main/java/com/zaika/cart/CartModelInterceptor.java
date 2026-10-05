package com.zaika.cart;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives every rendered page and htmx fragment the cart for the layout: {@code cartCount} (header badge) and
 * {@code cartSummary} (phone "View cart" bar; {@code null} for staff, who have no cart). It runs after the handler,
 * so an htmx answer to a cart change already shows the new count, and it also covers the view-controller pages
 * (About, Terms, ...) that a {@code @ControllerAdvice} never reaches. Redirects and REST answers are skipped. One
 * query for customers, at most one for guests.
 */
@Configuration(proxyBeanMethods = false)
class CartModelInterceptor implements HandlerInterceptor, WebMvcConfigurer {

	private final CartService carts;

	CartModelInterceptor(CartService carts) {
		this.carts = carts;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this);
	}

	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
			ModelAndView mav) {
		if (mav == null || !mav.isReference() || mav.getViewName().startsWith("redirect:")) {
			return;
		}
		CartOwner owner = CartOwner.of(request, false);
		CartSummary summary = owner == null ? null : carts.summary(owner);
		mav.addObject("cartSummary", summary);
		mav.addObject("cartCount", summary == null ? 0 : summary.count());
	}

}
