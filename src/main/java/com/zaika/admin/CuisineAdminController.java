package com.zaika.admin;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.admin.CuisineAdminService.Row;
import com.zaika.common.NotFoundException;

/**
 * Cuisines ({@code /admin/cuisines}): one page listing them with an add form, and a rename and delete per row.
 * Plain Post/Redirect/Get; a name that fails validation re-renders the page with that form open and the error inline.
 */
@Controller
class CuisineAdminController {

	static final String BASE = "/admin/cuisines";

	static final String VIEW = "admin/cuisines";

	private final CuisineAdminService cuisines;

	CuisineAdminController(CuisineAdminService cuisines) {
		this.cuisines = cuisines;
	}

	@GetMapping(BASE)
	String list(Model model) {
		return page(new CuisineForm(), new CuisineForm(), null, model);
	}

	@PostMapping(BASE)
	String create(@Valid @ModelAttribute("newCuisine") CuisineForm form, BindingResult errors, Model model,
			RedirectAttributes redirect) {
		if (!errors.hasErrors()) {
			try {
				redirect.addFlashAttribute("flashSuccess",
						cuisines.create(form.getName()) + " is added. Restaurants can pick it on their profile now.");
				return "redirect:" + BASE;
			}
			catch (FieldRuleException ex) {
				errors.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
			catch (DataIntegrityViolationException ex) { // added by someone else a moment ago
				errors.rejectValue("name", "taken", "There is already a cuisine called " + form.getName() + ".");
			}
		}
		return page(form, new CuisineForm(), null, model);
	}

	@PostMapping(BASE + "/{id}/rename")
	String rename(@PathVariable long id, @Valid @ModelAttribute("renameForm") CuisineForm form, BindingResult errors,
			Model model, RedirectAttributes redirect) {
		if (!errors.hasErrors()) {
			try {
				String before = cuisines.rename(id, form.getName());
				redirect.addFlashAttribute("flashSuccess", before.equals(form.getName()) ? "The name is unchanged."
						: before + " is now called " + form.getName() + ".");
				return "redirect:" + BASE + "#cuisine-" + id;
			}
			catch (FieldRuleException ex) {
				errors.rejectValue(ex.getField(), "rule", ex.getMessage());
			}
			catch (DataIntegrityViolationException ex) {
				errors.rejectValue("name", "taken", "There is already a cuisine called " + form.getName() + ".");
			}
		}
		return page(new CuisineForm(), form, id, model);
	}

	@PostMapping(BASE + "/{id}/delete")
	String delete(@PathVariable long id, RedirectAttributes redirect) {
		try {
			redirect.addFlashAttribute("flashSuccess", cuisines.delete(id) + " is deleted.");
		}
		catch (ModerationException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		catch (DataIntegrityViolationException ex) { // a restaurant picked it a moment ago
			redirect.addFlashAttribute("flashError", "A restaurant just picked this cuisine, so it stays.");
		}
		return "redirect:" + BASE;
	}

	/** {@code renaming}: the cuisine whose rename failed, shown open with its error; null otherwise. */
	private String page(CuisineForm newCuisine, CuisineForm renameForm, Long renaming, Model model) {
		List<Row> rows = cuisines.list();
		if (renaming != null && rows.stream().noneMatch(r -> r.cuisine().getId().equals(renaming))) {
			throw new NotFoundException("No cuisine " + renaming);
		}
		model.addAttribute("rows", rows);
		model.addAttribute("newCuisine", newCuisine);
		model.addAttribute("renameForm", renameForm);
		model.addAttribute("renaming", renaming);
		return VIEW;
	}

}
