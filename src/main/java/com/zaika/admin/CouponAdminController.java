package com.zaika.admin;

import java.time.Clock;
import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.admin.CouponAdminService.Row;
import com.zaika.common.Requests;
import com.zaika.coupon.CouponType;

/**
 * Coupons ({@code /admin/coupons}): the list with usage, the create page ({@code /new}) and the edit page per coupon
 * ({@code /{id}}), each form posting to its own URL and re-rendering with inline errors; delete from the edit page.
 * Plain Post/Redirect/Get with flash messages.
 */
@Controller
class CouponAdminController {

	static final String BASE = "/admin/coupons";

	static final String LIST_VIEW = "admin/coupons";

	static final String FORM_VIEW = "admin/coupon";

	private final CouponAdminService coupons;

	private final Clock clock;

	CouponAdminController(CouponAdminService coupons, Clock clock) {
		this.coupons = coupons;
		this.clock = clock;
	}

	/** Canonical paging: no {@code page=1}, nothing unreadable, nothing past the last page. */
	@GetMapping(BASE)
	String list(@RequestParam(required = false) String page, HttpServletRequest request, Model model) {
		int asked = Math.max(1, Requests.pageNumber(page));
		Page<Row> rows = coupons.list(asked);
		int last = Math.max(rows.getTotalPages(), 1);
		String canonical = asked > 1 ? BASE + "?page=" + Math.min(asked, last) : BASE;
		if (!canonical.equals(Requests.currentUrl(request))) {
			return "redirect:" + canonical;
		}
		model.addAttribute("rows", rows);
		model.addAttribute("listUrl", BASE);
		return LIST_VIEW;
	}

	@GetMapping(BASE + "/new")
	String newCoupon(Model model) {
		return form(null, CouponForm.starting(LocalDateTime.now(clock)), model);
	}

	@PostMapping(BASE + "/new")
	String create(@Valid @ModelAttribute("form") CouponForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		form.check(errors);
		if (!errors.hasErrors()) {
			try {
				String code = coupons.create(form);
				redirect.addFlashAttribute("flashSuccess", code + " is saved. " + (form.isActive()
						? "Customers can use it from its start time." : "It stays off until you switch it on."));
				return "redirect:" + BASE;
			}
			catch (FieldRuleException ex) {
				errors.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
			catch (DataIntegrityViolationException ex) { // the same code saved a moment ago
				errors.rejectValue("code", "taken", form.getCode() + " is already taken. Pick another code.");
			}
		}
		return form(null, form, model);
	}

	@GetMapping(BASE + "/{id}")
	String edit(@PathVariable long id, Model model) {
		Row row = coupons.detail(id);
		return form(row, CouponForm.of(row.coupon()), model);
	}

	/** The code never changes: the page shows it read-only, and the stored one is kept whatever was posted. */
	@PostMapping(BASE + "/{id}")
	String update(@PathVariable long id, @Valid @ModelAttribute("form") CouponForm form, BindingResult errors,
			Model model, RedirectAttributes redirect) {
		Row row = coupons.detail(id); // unknown ids are a 404 before anything else
		form.setCode(row.coupon().getCode());
		form.check(errors);
		if (!errors.hasErrors()) {
			try {
				String code = coupons.update(id, form);
				redirect.addFlashAttribute("flashSuccess", code + " is updated.");
				return "redirect:" + BASE + "/" + id;
			}
			catch (FieldRuleException ex) {
				errors.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
		}
		return form(row, form, model);
	}

	@PostMapping(BASE + "/{id}/delete")
	String delete(@PathVariable long id, RedirectAttributes redirect) {
		try {
			redirect.addFlashAttribute("flashSuccess", coupons.delete(id) + " is deleted.");
			return "redirect:" + BASE;
		}
		catch (ModerationException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
			return "redirect:" + BASE + "/" + id;
		}
	}

	private String form(Row row, CouponForm form, Model model) {
		model.addAttribute("row", row);
		model.addAttribute("form", form);
		model.addAttribute("types", CouponType.values());
		model.addAttribute("restaurants", coupons.restaurants());
		return FORM_VIEW;
	}

}
