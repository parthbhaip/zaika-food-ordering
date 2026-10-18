package com.zaika.notification;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.zaika.security.CurrentUser;

/**
 * Gives every full page rendered for a signed-in account its unread count ({@code unreadNotifications}) for the
 * header bell: one query. Redirects, REST answers and htmx fragments (which never render the header) are skipped, as
 * are pages whose controller already set the count.
 */
@Configuration(proxyBeanMethods = false)
class NotificationModelInterceptor implements HandlerInterceptor, WebMvcConfigurer {

	static final String UNREAD = "unreadNotifications";

	private final NotificationService notifications;

	NotificationModelInterceptor(NotificationService notifications) {
		this.notifications = notifications;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this);
	}

	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
			ModelAndView mav) {
		if (mav == null || !mav.isReference() || mav.getViewName().startsWith("redirect:")
				|| mav.getModel().containsKey(UNREAD)
				|| (request.getHeader("HX-Request") != null && request.getHeader("HX-History-Restore-Request") == null)) {
			return;
		}
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof CurrentUser me) {
			mav.addObject(UNREAD, notifications.unreadCount(me.getId()));
		}
	}

}
