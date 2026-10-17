package com.zaika.admin;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.admin.UserAdminService.Detail;
import com.zaika.common.Requests;
import com.zaika.security.CurrentUser;
import com.zaika.user.Role;
import com.zaika.user.User;

/**
 * Accounts ({@code /admin/users}): the list with role chips and search, one account with the orders it placed
 * ({@code /admin/users/{id}?page=}), and enable / disable, plain Post/Redirect/Get.
 */
@Controller
class UserAdminController {

	static final String LIST_VIEW = "admin/users";

	static final String DETAIL_VIEW = "admin/user";

	private final UserAdminService accounts;

	UserAdminController(UserAdminService accounts) {
		this.accounts = accounts;
	}

	@GetMapping(ModerationFilter.USERS)
	String list(@RequestParam(required = false) String role, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, HttpServletRequest request, Model model) {
		ModerationFilter<Role> filter = ModerationFilter.users(role, q, page);
		Queue<User, Role> queue = accounts.list(filter);
		filter.setPage(Math.min(filter.getPage(), Math.max(queue.page().getTotalPages(), 1)));
		if (!filter.url().equals(Requests.currentUrl(request))) {
			return "redirect:" + filter.url();
		}
		model.addAttribute("filter", filter);
		model.addAttribute("queue", queue);
		model.addAttribute("roles", Role.values());
		return LIST_VIEW;
	}

	/** Canonical paging of the orders: no {@code page=1}, nothing unreadable, nothing past the last page. */
	@GetMapping(ModerationFilter.USERS + "/{id}")
	String detail(@PathVariable long id, @RequestParam(required = false) String page,
			@AuthenticationPrincipal CurrentUser me, HttpServletRequest request, Model model) {
		int asked = Math.max(1, Requests.pageNumber(page));
		Detail d = accounts.detail(me.getId(), id, asked);
		int last = Math.max(d.orders().getTotalPages(), 1);
		String base = ModerationFilter.USERS + "/" + id;
		String canonical = asked > 1 ? base + "?page=" + Math.min(asked, last) : base;
		if (!canonical.equals(Requests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		model.addAttribute("d", d);
		model.addAttribute("ordersUrl", base);
		return DETAIL_VIEW;
	}

	@PostMapping(ModerationFilter.USERS + "/{id}/disable")
	String disable(@PathVariable long id, @AuthenticationPrincipal CurrentUser me, RedirectAttributes redirect) {
		return change(me, id, false, redirect);
	}

	@PostMapping(ModerationFilter.USERS + "/{id}/enable")
	String enable(@PathVariable long id, @AuthenticationPrincipal CurrentUser me, RedirectAttributes redirect) {
		return change(me, id, true, redirect);
	}

	private String change(CurrentUser me, long id, boolean enabled, RedirectAttributes redirect) {
		try {
			String name = accounts.setEnabled(me.getId(), id, enabled);
			redirect.addFlashAttribute("flashSuccess", enabled ? name + "'s account is active. They can sign in again."
					: name + "'s account is disabled and signed out everywhere.");
		}
		catch (ModerationException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:" + ModerationFilter.USERS + "/" + id;
	}

}
