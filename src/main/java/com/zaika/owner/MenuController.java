package com.zaika.owner;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuItem;
import com.zaika.owner.MenuManagementService.DishPage;
import com.zaika.owner.MenuManagementService.Removed;
import com.zaika.security.CurrentUser;

/**
 * Menu management ({@code /owner/menu}): the menu page with category actions (add, rename, move, delete) and each
 * dish's sold-out switch and delete, plus the add / edit dish pages. Everything is a plain form with
 * Post/Redirect/Get; the sold-out switch also works through htmx, swapping only its dish row. The dish forms upload a
 * photo, so they POST to the URL that shows them (see {@code common.UploadLimitFilter}).
 */
@Controller
@RequestMapping("/owner/menu")
class MenuController {

	private final MenuManagementService menu;

	MenuController(MenuManagementService menu) {
		this.menu = menu;
	}

	@GetMapping
	String menu(@AuthenticationPrincipal CurrentUser me, Model model) {
		return menuPage(me, model, null);
	}

	// ------------------------------------------------------------------------------------------- categories

	@PostMapping("/categories")
	String addCategory(@Valid @ModelAttribute("newCategory") CategoryForm form, BindingResult result,
			@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				MenuCategory category = menu.addCategory(me.getId(), form.getName());
				redirect.addFlashAttribute("flashSuccess", "“" + category.getName() + "” added. Now add its dishes.");
				return "redirect:/owner/menu#category-" + category.getId();
			}
			catch (MenuRuleException ex) {
				result.rejectValue("name", "rule", ex.getMessage());
			}
		}
		return menuPage(me, model, null);
	}

	@PostMapping("/categories/{id}/rename")
	String renameCategory(@PathVariable long id, @Valid @ModelAttribute("renameForm") CategoryForm form,
			BindingResult result, @AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				MenuCategory category = menu.renameCategory(me.getId(), id, form.getName());
				redirect.addFlashAttribute("flashSuccess", "Category renamed to “" + category.getName() + "”.");
				return "redirect:/owner/menu#category-" + id;
			}
			catch (MenuRuleException ex) {
				result.rejectValue("name", "rule", ex.getMessage());
			}
		}
		return menuPage(me, model, id);
	}

	@PostMapping("/categories/{id}/move")
	String moveCategory(@PathVariable long id, @RequestParam Direction direction,
			@AuthenticationPrincipal CurrentUser me, RedirectAttributes redirect) {
		MenuCategory category = menu.moveCategory(me.getId(), id, direction == Direction.UP);
		redirect.addFlashAttribute("flashSuccess",
				"“" + category.getName() + "” moved " + direction.name().toLowerCase() + ".");
		return "redirect:/owner/menu#category-" + id;
	}

	@PostMapping("/categories/{id}/delete")
	String deleteCategory(@PathVariable long id, @AuthenticationPrincipal CurrentUser me,
			RedirectAttributes redirect) {
		try {
			MenuCategory category = menu.deleteCategory(me.getId(), id);
			redirect.addFlashAttribute("flashSuccess", "“" + category.getName() + "” deleted.");
			return "redirect:/owner/menu";
		}
		catch (MenuRuleException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
			return "redirect:/owner/menu#category-" + id;
		}
	}

	// ------------------------------------------------------------------------------------------- dishes

	@GetMapping("/items/new")
	String newDish(@RequestParam(name = "category", required = false) Long categoryId,
			@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		DishPage page = menu.newDish(me.getId());
		if (page.categories().isEmpty()) {
			redirect.addFlashAttribute("flashInfo", "Add a category first, like Starters or Thalis. Dishes go in it.");
			return "redirect:/owner/menu";
		}
		MenuItemForm form = new MenuItemForm();
		form.setCategoryId(page.categories().stream()
			.map(MenuCategory::getId)
			.filter(id -> id.equals(categoryId))
			.findFirst()
			.orElse(page.categories().get(0).getId()));
		if (page.restaurant().isPureVeg()) {
			form.setVeg(true);
		}
		model.addAttribute("form", form);
		return dishPage(model, page);
	}

	@PostMapping("/items/new")
	String addDish(@Valid @ModelAttribute("form") MenuItemForm form, BindingResult result,
			@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				MenuItem item = menu.addDish(me.getId(), form);
				redirect.addFlashAttribute("flashSuccess", "“" + item.getName() + "” is on your menu.");
				return "redirect:/owner/menu#dish-" + item.getId();
			}
			catch (MenuRuleException ex) {
				result.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
		}
		return dishPage(model, menu.newDish(me.getId()));
	}

	@GetMapping("/items/{id}")
	String editDish(@PathVariable long id, @AuthenticationPrincipal CurrentUser me, Model model) {
		DishPage page = menu.editDish(me.getId(), id);
		model.addAttribute("form", MenuItemForm.of(page.item()));
		return dishPage(model, page);
	}

	@PostMapping("/items/{id}")
	String updateDish(@PathVariable long id, @Valid @ModelAttribute("form") MenuItemForm form, BindingResult result,
			@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				MenuItem item = menu.updateDish(me.getId(), id, form);
				redirect.addFlashAttribute("flashSuccess", "“" + item.getName() + "” saved.");
				return "redirect:/owner/menu#dish-" + id;
			}
			catch (MenuRuleException ex) {
				result.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
		}
		return dishPage(model, menu.editDish(me.getId(), id));
	}

	@PostMapping("/items/{id}/availability")
	String availability(@PathVariable long id, @RequestParam boolean available,
			@AuthenticationPrincipal CurrentUser me, HttpServletRequest request, Model model,
			RedirectAttributes redirect) {
		MenuItem item = menu.setAvailable(me.getId(), id, available);
		String message = available ? "“" + item.getName() + "” is back in stock."
				: "“" + item.getName() + "” marked sold out. Customers see it greyed out.";
		if (!"true".equals(request.getHeader("HX-Request"))) {
			redirect.addFlashAttribute(available ? "flashSuccess" : "flashInfo", message);
			return "redirect:/owner/menu#dish-" + id;
		}
		model.addAttribute("item", item);
		model.addAttribute("toastKind", available ? "success" : "info");
		model.addAttribute("toastMessage", message);
		return "owner/menu :: dish-update";
	}

	@PostMapping("/items/{id}/delete")
	String deleteDish(@PathVariable long id, @AuthenticationPrincipal CurrentUser me, RedirectAttributes redirect) {
		Removed removed = menu.deleteDish(me.getId(), id);
		redirect.addFlashAttribute("flashSuccess", removed.archived()
				? "“" + removed.name() + "” is off your menu. Past orders still show it."
				: "“" + removed.name() + "” deleted.");
		return "redirect:/owner/menu";
	}

	// ------------------------------------------------------------------------------------------- pages

	/** The menu page; {@code renaming} is the category whose rename form failed (shown open), or null. */
	private String menuPage(CurrentUser me, Model model, Long renaming) {
		model.addAttribute("menu", menu.menu(me.getId()));
		if (!model.containsAttribute("newCategory")) {
			model.addAttribute("newCategory", new CategoryForm());
		}
		if (!model.containsAttribute("renameForm")) {
			model.addAttribute("renameForm", new CategoryForm());
		}
		model.addAttribute("renaming", renaming);
		return "owner/menu";
	}

	private static String dishPage(Model model, DishPage page) {
		model.addAttribute("page", page);
		model.addAttribute("spiceLabels", MenuItemForm.SPICE_LABELS);
		return "owner/menu-item";
	}

	enum Direction {
		UP, DOWN
	}

}
