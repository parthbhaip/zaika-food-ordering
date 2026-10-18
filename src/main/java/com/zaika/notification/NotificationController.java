package com.zaika.notification;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.common.Requests;
import com.zaika.security.CurrentUser;

/**
 * Every signed-in account's inbox: {@code /notifications} (20 to a page, newest first, canonical {@code ?page=}),
 * "Mark all as read", and opening one (marks it read, then goes where it points). The header bell polls
 * {@code /notifications/badge?n=<shown count>} every 30 seconds: 204 while the count is unchanged, otherwise the new
 * poller plus the count out of band.
 */
@Controller
@RequestMapping("/notifications")
class NotificationController {

	static final String LIST_VIEW = "notification/list";

	private final NotificationService notifications;

	NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	String list(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String page, Model model) {
		int number = Requests.pageNumber(page);
		if (number < 1 || "1".equals(page)) { // unreadable, out of range, or spelling out the default
			return "redirect:/notifications";
		}
		Page<Notification> list = notifications.page(me.getId(), number - 1);
		if (number > 1 && number > list.getTotalPages()) {
			return list.getTotalPages() > 1 ? "redirect:/notifications?page=" + list.getTotalPages()
					: "redirect:/notifications";
		}
		model.addAttribute("notifications", list);
		model.addAttribute(NotificationModelInterceptor.UNREAD, notifications.unreadCount(me.getId()));
		return LIST_VIEW;
	}

	@GetMapping("/badge")
	String badge(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) Long n, Model model,
			HttpServletResponse response) {
		long unread = notifications.unreadCount(me.getId());
		if (n != null && n == unread) {
			response.setStatus(HttpStatus.NO_CONTENT.value());
			return null; // htmx swaps nothing on a 204
		}
		model.addAttribute(NotificationModelInterceptor.UNREAD, unread);
		return "fragments/header :: bell-update";
	}

	@PostMapping("/read-all")
	String readAll(@AuthenticationPrincipal CurrentUser me, RedirectAttributes redirect) {
		int marked = notifications.markAllRead(me.getId());
		redirect.addFlashAttribute("flashSuccess", marked == 0 ? "You're all caught up."
				: marked == 1 ? "Marked 1 notification as read." : "Marked " + marked + " notifications as read.");
		return "redirect:/notifications";
	}

	@PostMapping("/{id}/open")
	String open(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		return "redirect:" + notifications.open(me.getId(), id);
	}

}
