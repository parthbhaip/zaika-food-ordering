package com.zaika.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zaika.security.CurrentUser;
import com.zaika.user.AddressForm;
import com.zaika.user.AddressService;

/**
 * The customer's address book (CUSTOMER tokens only, see ApiSecurityConfig), with the website's rules and messages:
 * at most {@link AddressService#MAX_ADDRESSES} (409 beyond), one default. The body is the website's address form as
 * JSON: {@code label} (HOME, WORK, OTHER), {@code house}, {@code street}, {@code landmark}, {@code city},
 * {@code pincode}, {@code makeDefault}. Another account's address id is a 404.
 */
@RestController
@RequestMapping("/api/v1/addresses")
class AddressApiController {

	private final AddressService addresses;

	AddressApiController(AddressService addresses) {
		this.addresses = addresses;
	}

	/** Default first, then newest first. */
	@GetMapping
	List<AddressResponse> list(@AuthenticationPrincipal CurrentUser me) {
		return addresses.list(me.getId()).stream().map(AddressResponse::of).toList();
	}

	@GetMapping("/{id}")
	AddressResponse get(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		return AddressResponse.of(addresses.get(me.getId(), id));
	}

	/** 201 with the new address; the first one becomes the default. */
	@PostMapping
	ResponseEntity<AddressResponse> create(@AuthenticationPrincipal CurrentUser me,
			@Valid @RequestBody AddressForm form) {
		AddressResponse saved = AddressResponse.of(addresses.create(me.getId(), form));
		return ResponseEntity.created(URI.create("/api/v1/addresses/" + saved.id())).body(saved);
	}

	/** Replaces every field; {@code makeDefault: true} makes it the default (false leaves the default as it is). */
	@PutMapping("/{id}")
	AddressResponse update(@AuthenticationPrincipal CurrentUser me, @PathVariable long id,
			@Valid @RequestBody AddressForm form) {
		return AddressResponse.of(addresses.update(me.getId(), id, form));
	}

	/** 204; deleting the default makes the newest remaining address the default. */
	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser me, @PathVariable long id) {
		addresses.delete(me.getId(), id);
		return ResponseEntity.noContent().build();
	}

}
