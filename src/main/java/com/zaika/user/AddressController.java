package com.zaika.user;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zaika.security.CurrentUser;

/**
 * The address book under /account/addresses. Ids in the URL are checked against the signed-in user by
 * {@link AddressService}; someone else's id is a 404.
 */
@Controller
@RequestMapping("/account/addresses")
class AddressController {

	static final String LIST = "account/addresses";

	static final String FORM = "account/address-form";

	static final String FULL = "You can save up to " + AddressService.MAX_ADDRESSES
			+ " addresses. Delete one to add another.";

	private final AddressService addressService;

	AddressController(AddressService addressService) {
		this.addressService = addressService;
	}

	@GetMapping
	String list(@AuthenticationPrincipal CurrentUser me, Model model) {
		model.addAttribute("addresses", addressService.list(me.getId()));
		model.addAttribute("max", AddressService.MAX_ADDRESSES);
		return LIST;
	}

	@GetMapping("/new")
	String newForm(@AuthenticationPrincipal CurrentUser me, Model model, RedirectAttributes redirect) {
		if (addressService.isFull(me.getId())) {
			redirect.addFlashAttribute("flashError", FULL);
			return "redirect:/account/addresses";
		}
		model.addAttribute("form", new AddressForm());
		return FORM;
	}

	@PostMapping
	String create(@AuthenticationPrincipal CurrentUser me, @Valid @ModelAttribute("form") AddressForm form,
			BindingResult result, RedirectAttributes redirect) {
		if (result.hasErrors()) {
			return FORM;
		}
		try {
			Address saved = addressService.create(me.getId(), form);
			redirect.addFlashAttribute("flashSuccess", saved.getLabel().getDisplayName() + " address saved.");
		}
		catch (AddressLimitException ex) {
			redirect.addFlashAttribute("flashError", FULL);
		}
		return "redirect:/account/addresses";
	}

	@GetMapping("/{id}/edit")
	String editForm(@AuthenticationPrincipal CurrentUser me, @PathVariable long id, Model model) {
		Address address = addressService.get(me.getId(), id);
		model.addAttribute("address", address);
		model.addAttribute("form", AddressForm.of(address));
		return FORM;
	}

	@PostMapping("/{id}")
	String update(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@Valid @ModelAttribute("form") AddressForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("address", addressService.get(me.getId(), id));
			return FORM;
		}
		Address saved = addressService.update(me.getId(), id, form);
		redirect.addFlashAttribute("flashSuccess", saved.getLabel().getDisplayName() + " address updated.");
		return "redirect:/account/addresses";
	}

	@PostMapping("/{id}/default")
	String makeDefault(@AuthenticationPrincipal CurrentUser me, @PathVariable long id, RedirectAttributes redirect) {
		addressService.makeDefault(me.getId(), id);
		redirect.addFlashAttribute("flashSuccess", "Default address updated. Checkout will pick it first.");
		return "redirect:/account/addresses";
	}

	@PostMapping("/{id}/delete")
	String delete(@AuthenticationPrincipal CurrentUser me, @PathVariable long id, RedirectAttributes redirect) {
		addressService.delete(me.getId(), id);
		redirect.addFlashAttribute("flashSuccess", "Address deleted.");
		return "redirect:/account/addresses";
	}

}
