package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.security.ZaikaUserDetailsService;

/** The address book pages: list, add, edit, default, delete, the cap, and another customer's address -> 404. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AddressBookPageTest {

	static final String ASHA = "asha@address-test.zaika.test";

	static final String RAVI = "ravi@address-test.zaika.test";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	AddressService addresses;

	@Autowired
	ZaikaUserDetailsService userDetails;

	long ashaId;

	@BeforeEach
	void createCustomers() {
		ashaId = users.save(new User("Asha", ASHA, "9876543210", "{noop}x", Role.CUSTOMER)).getId();
		users.save(new User("Ravi", RAVI, "9876543211", "{noop}x", Role.CUSTOMER));
	}

	@AfterEach
	void deleteAccounts() {
		// addresses go with their user (ON DELETE CASCADE)
		users.findAll().stream().filter(u -> u.getEmail().endsWith("@address-test.zaika.test")).forEach(users::delete);
	}

	RequestPostProcessor as(String email) {
		return user(userDetails.loadUserByUsername(email));
	}

	static MockHttpServletRequestBuilder addressParams(MockHttpServletRequestBuilder request) {
		return request.with(csrf())
			.param("label", "WORK")
			.param("house", "Office 12, Laxmi Complex")
			.param("street", "Station Road, Shahupuri")
			.param("landmark", "Opposite the bus stand")
			.param("city", "Kolhapur")
			.param("pincode", "416 001");
	}

	Address saveFor(long userId, AddressLabel label, String house) {
		AddressForm form = new AddressForm();
		form.setLabel(label);
		form.setHouse(house);
		form.setStreet("Rajarampuri 5th Lane");
		form.setCity("Kolhapur");
		form.setPincode("416008");
		return addresses.create(userId, form);
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	@Test
	void emptyAddressBookInvitesTheFirstAddress() throws Exception {
		String html = squash(mvc.perform(get("/account/addresses").with(as(ASHA)))
			.andExpect(status().isOk())
			.andExpect(view().name("account/addresses"))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("<title>Saved addresses | Zaika</title>", "No saved addresses yet",
					"href=\"/account/addresses/new\">Add your first address</a>",
					"<a class=\"tab\" href=\"/account/addresses\" aria-current=\"page\">")
			.doesNotContain("address-card");
	}

	@Test
	void addingAnAddressSavesItForTheSignedInCustomer() throws Exception {
		String form = squash(mvc.perform(get("/account/addresses/new").with(as(ASHA)))
			.andExpect(status().isOk())
			.andExpect(view().name("account/address-form"))
			.andReturn().getResponse().getContentAsString());
		assertThat(form)
			.contains("<title>Add an address | Zaika</title>", "action=\"/account/addresses\"",
					"type=\"radio\" name=\"label\" value=\"HOME\" id=\"label-HOME\" checked",
					"name=\"city\" value=\"Kolhapur\"", "autocomplete=\"postal-code\" inputmode=\"numeric\"",
					"name=\"makeDefault\"", "Save address")
			.doesNotContain("field-error");

		mvc.perform(addressParams(post("/account/addresses")).with(as(ASHA)))
			.andExpect(redirectedUrl("/account/addresses"))
			.andExpect(flash().attribute("flashSuccess", "Work address saved."));

		Address saved = addresses.list(ashaId).getFirst();
		assertThat(saved.getLabel()).isEqualTo(AddressLabel.WORK);
		assertThat(saved.getPincode()).isEqualTo("416001");
		assertThat(saved.getLandmark()).isEqualTo("Opposite the bus stand");
		assertThat(saved.isDefaultAddress()).isTrue();

		String list = squash(mvc.perform(get("/account/addresses").with(as(ASHA)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(list)
			.contains("1 of 10 saved.", "<span>Work</span>", "Office 12, Laxmi Complex", "Near Opposite the bus stand",
					"Kolhapur 416001", "Default", "href=\"/account/addresses/" + saved.getId() + "/edit\"",
					"action=\"/account/addresses/" + saved.getId() + "/delete\"", "Yes, delete")
			.doesNotContain("No saved addresses yet",
					"action=\"/account/addresses/" + saved.getId() + "/default\"");
	}

	@Test
	void invalidAddressShowsInlineErrorsAndKeepsTheInput() throws Exception {
		String html = squash(mvc.perform(post("/account/addresses").with(csrf()).with(as(ASHA))
				.param("label", "HOME").param("house", "Flat 4B").param("street", " ").param("city", "Kolhapur")
				.param("pincode", "041600"))
			.andExpect(status().isOk())
			.andExpect(view().name("account/address-form"))
			.andExpect(model().attributeHasFieldErrors("form", "street", "pincode"))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("The address was not saved.", "Enter the street or area.",
					"Enter a valid 6-digit PIN code, like 416008.", "name=\"house\" value=\"Flat 4B\"",
					"name=\"pincode\" value=\"041600\"")
			.doesNotContain("Edit address");
		assertThat(addresses.list(ashaId)).isEmpty();

		mvc.perform(post("/account/addresses").with(csrf()).with(as(ASHA)).param("label", "PALACE")
				.param("house", "Flat 4B").param("street", "Rajarampuri").param("city", "Kolhapur").param("pincode", "416008"))
			.andExpect(model().attributeHasFieldErrorCode("form", "label", "typeMismatch"))
			.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Choose Home, Work or Other."));
	}

	@Test
	void editingChangesTheAddressAndCanMakeItTheDefault() throws Exception {
		Address home = saveFor(ashaId, AddressLabel.HOME, "Flat 4B");
		Address work = saveFor(ashaId, AddressLabel.WORK, "Office 12");

		String form = squash(mvc.perform(get("/account/addresses/" + work.getId() + "/edit").with(as(ASHA)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(form)
			.contains("<title>Edit address | Zaika</title>", "action=\"/account/addresses/" + work.getId() + "\"",
					"name=\"house\" value=\"Office 12\"", "value=\"WORK\" id=\"label-WORK\" checked", "Save changes",
					"name=\"makeDefault\"");

		mvc.perform(addressParams(post("/account/addresses/" + work.getId())).with(as(ASHA)).param("makeDefault", "true"))
			.andExpect(redirectedUrl("/account/addresses"))
			.andExpect(flash().attribute("flashSuccess", "Work address updated."));
		assertThat(addresses.get(ashaId, work.getId()).getHouse()).isEqualTo("Office 12, Laxmi Complex");
		assertThat(addresses.get(ashaId, work.getId()).isDefaultAddress()).isTrue();
		assertThat(addresses.get(ashaId, home.getId()).isDefaultAddress()).isFalse();

		// the default's edit form says so instead of offering the checkbox
		assertThat(mvc.perform(get("/account/addresses/" + work.getId() + "/edit").with(as(ASHA)))
			.andReturn().getResponse().getContentAsString())
			.contains("This is your default address.").doesNotContain("name=\"makeDefault\"");
	}

	@Test
	void setAsDefaultAndDelete() throws Exception {
		Address home = saveFor(ashaId, AddressLabel.HOME, "Flat 4B");
		Address work = saveFor(ashaId, AddressLabel.WORK, "Office 12");

		mvc.perform(post("/account/addresses/" + work.getId() + "/default").with(csrf()).with(as(ASHA)))
			.andExpect(redirectedUrl("/account/addresses"))
			.andExpect(flash().attribute("flashSuccess", "Default address updated. Checkout will pick it first."));
		assertThat(addresses.list(ashaId).getFirst()).isEqualTo(work);

		mvc.perform(post("/account/addresses/" + work.getId() + "/delete").with(csrf()).with(as(ASHA)))
			.andExpect(redirectedUrl("/account/addresses"))
			.andExpect(flash().attribute("flashSuccess", "Address deleted."));
		assertThat(addresses.list(ashaId)).containsExactly(home);
		assertThat(addresses.get(ashaId, home.getId()).isDefaultAddress()).isTrue();
	}

	@Test
	void anotherCustomersAddressIsA404EverywhereAndStaysUntouched() throws Exception {
		Address home = saveFor(ashaId, AddressLabel.HOME, "Flat 4B");
		String base = "/account/addresses/" + home.getId();

		mvc.perform(get(base + "/edit").with(as(RAVI)))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"));
		mvc.perform(addressParams(post(base)).with(as(RAVI))).andExpect(status().isNotFound());
		// even an invalid edit of someone else's address is a 404, not a form with errors
		mvc.perform(post(base).with(csrf()).with(as(RAVI))).andExpect(status().isNotFound());
		mvc.perform(post(base + "/default").with(csrf()).with(as(RAVI))).andExpect(status().isNotFound());
		mvc.perform(post(base + "/delete").with(csrf()).with(as(RAVI))).andExpect(status().isNotFound());

		assertThat(mvc.perform(get("/account/addresses").with(as(RAVI))).andReturn().getResponse().getContentAsString())
			.doesNotContain("Flat 4B");
		Address unchanged = addresses.get(ashaId, home.getId());
		assertThat(unchanged.getHouse()).isEqualTo("Flat 4B");
		assertThat(unchanged.getLabel()).isEqualTo(AddressLabel.HOME);
	}

	@Test
	void unknownIdsAndBadIdsAre404() throws Exception {
		mvc.perform(get("/account/addresses/999999999/edit").with(as(ASHA))).andExpect(status().isNotFound());
		mvc.perform(post("/account/addresses/999999999/delete").with(csrf()).with(as(ASHA))).andExpect(status().isNotFound());
	}

	@Test
	void theTenthAddressFillsTheBook() throws Exception {
		for (int i = 1; i <= AddressService.MAX_ADDRESSES; i++) {
			saveFor(ashaId, AddressLabel.OTHER, "House " + i);
		}
		String list = squash(mvc.perform(get("/account/addresses").with(as(ASHA)))
			.andReturn().getResponse().getContentAsString());
		assertThat(list).contains("10 of 10 saved.", "Your address book is full (10 addresses).")
			.doesNotContain("href=\"/account/addresses/new\"");

		mvc.perform(get("/account/addresses/new").with(as(ASHA)))
			.andExpect(redirectedUrl("/account/addresses"))
			.andExpect(flash().attribute("flashError", "You can save up to 10 addresses. Delete one to add another."));
		mvc.perform(addressParams(post("/account/addresses")).with(as(ASHA)))
			.andExpect(redirectedUrl("/account/addresses"))
			.andExpect(flash().attribute("flashError", "You can save up to 10 addresses. Delete one to add another."));
		assertThat(addresses.list(ashaId)).hasSize(10);
	}

	@Test
	void addressActionsNeedACsrfTokenAndTheCustomerRole() throws Exception {
		Address home = saveFor(ashaId, AddressLabel.HOME, "Flat 4B");
		mvc.perform(post("/account/addresses/" + home.getId() + "/delete").with(as(ASHA)))
			.andExpect(status().isForbidden());
		mvc.perform(get("/account/addresses")).andExpect(redirectedUrl("/login"));
		User rider = users.save(new User("Rider", "rider@address-test.zaika.test", "9876500001", "{noop}x",
				Role.DELIVERY_PARTNER));
		mvc.perform(post("/account/addresses/" + home.getId() + "/delete").with(csrf())
			.with(user(userDetails.loadUserByUsername(rider.getEmail())))).andExpect(status().isForbidden());
		assertThat(addresses.list(ashaId)).containsExactly(home);
	}

}
