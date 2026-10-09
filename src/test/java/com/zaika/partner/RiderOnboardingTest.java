package com.zaika.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.zaika.catalog.Restaurant;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T34: the rider sign-up ({@code /partner-with-us/rider}: DELIVERY_PARTNER account + PENDING profile, signed in
 * straight away) and the rider panel's status banner for pending and suspended riders.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:rideronboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RiderOnboardingTest {

	static final String FORM = "/partner-with-us/rider";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	CatalogFixtures fixtures;

	// ----------------------------------------------------------------------- links and the form

	@Test
	void homeFooterAndPartnerPageLinkToTheRiderSignUpWhichAnswers200() throws Exception {
		assertThat(page(get("/"))).contains("href=\"/partner-with-us/rider\">Become a delivery partner</a>",
				"href=\"/partner-with-us/rider\">Ride with Zaika</a>");
		assertThat(page(get("/partner-with-us"))).contains("href=\"/partner-with-us/rider\">Become a delivery partner</a>");
		mvc.perform(get(FORM)).andExpect(status().isOk()).andExpect(view().name("partner/onboarding"));
	}

	@Test
	void signUpFormHasBothSectionsAndEveryField() throws Exception {
		String html = page(get(FORM));
		assertThat(html)
			.contains("<title>Become a delivery partner | Zaika</title>", "name=\"_csrf\"",
					"<span class=\"checkout-step-no\" aria-hidden=\"true\">1</span>About you</legend>",
					"<span class=\"checkout-step-no\" aria-hidden=\"true\">2</span>Your ride</legend>",
					"id=\"name\"", "id=\"phone\"", "id=\"email\"", "id=\"password\"", "id=\"confirmPassword\"",
					"type=\"radio\" name=\"vehicleType\" id=\"vehicle-bike\" value=\"BIKE\"",
					"type=\"radio\" name=\"vehicleType\" id=\"vehicle-scooter\" value=\"SCOOTER\"",
					"type=\"radio\" name=\"vehicleType\" id=\"vehicle-bicycle\" value=\"BICYCLE\"",
					"<strong>Motorbike</strong>", "<strong>Scooter</strong>", "<strong>Bicycle</strong>",
					"id=\"vehicleNumber\"", "Leave it empty if you ride a bicycle.",
					"<option value=\"\">Choose an area</option>", "<option value=\"Rajarampuri\">Rajarampuri</option>",
					"<option value=\"Tarabai Park\">Tarabai Park</option>", "id=\"acceptTerms\"", "Submit for review",
					"What happens next", "mailto:partners@zaika.test")
			.doesNotContain("role=\"alert\"", "aria-invalid", "checked=\"checked\"")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
			.doesNotContainPattern("\\son[a-z]+=");
	}

	// ----------------------------------------------------------------------- signing up

	@Test
	void validSignUpCreatesAPendingOfflineRiderAndSignsThemIn() throws Exception {
		MvcResult result = mvc.perform(signUp(Map.of("email", " Rahul@Riders.Zaika.Test ", "phone", "+91 98900 11221",
				"vehicleNumber", "mh 09-ek 4521")))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attribute("flashSuccess", "Thanks, Rahul! Your rider sign-up is in and waiting for review."))
			.andExpect(authenticated().withUsername("rahul@riders.zaika.test").withRoles("DELIVERY_PARTNER"))
			.andReturn();

		User rider = users.findByEmailIgnoreCase("rahul@riders.zaika.test").orElseThrow();
		assertThat(rider.getName()).isEqualTo("Rahul Pawar");
		assertThat(rider.getPhone()).isEqualTo("9890011221");
		assertThat(rider.getRole()).isEqualTo(Role.DELIVERY_PARTNER);
		assertThat(passwordEncoder.matches("Kolhapur2026", rider.getPasswordHash())).isTrue();

		DeliveryPartnerProfile p = profiles.findByUserId(rider.getId()).orElseThrow();
		assertThat(p.getStatus()).isEqualTo(RiderStatus.PENDING);
		assertThat(p.isOnline()).isFalse();
		assertThat(p.getVehicleType()).isEqualTo(VehicleType.BIKE);
		assertThat(p.getVehicleNumber()).isEqualTo("MH09EK4521");
		assertThat(p.getPreferredArea()).isEqualTo("Rajarampuri");
		assertThat(p.getStatusReason()).isNull();

		// the session is signed in: the rider panel opens with the review banner, other areas do not
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession();
		String dashboard = mvc.perform(get("/partner").session(session).flashAttrs(result.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(view().name("partner/dashboard"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(dashboard).contains("<title>Rider dashboard | Zaika</title>",
				"Thanks, Rahul! Your rider sign-up is in and waiting for review.",
				"<h1 class=\"owner-title\">Rahul Pawar</h1>", "Under review", "Based in Rajarampuri",
				"Waiting for review.", "You can go online and take deliveries once you're approved.",
				"Motorbike", "MH09EK4521", "Rajarampuri, Kolhapur", "+91 98900 11221", "rahul@riders.zaika.test");
		mvc.perform(get("/owner").session(session)).andExpect(status().isForbidden());
		mvc.perform(get("/orders").session(session)).andExpect(status().isForbidden());

		// and the new account signs in with the login form, landing on the rider panel
		mvc.perform(post("/login").param("email", "rahul@riders.zaika.test").param("password", "Kolhapur2026").with(csrf()))
			.andExpect(redirectedUrl("/partner"));
	}

	@Test
	void aBicycleNeedsNoNumberPlate() throws Exception {
		mvc.perform(signUp(Map.of("email", "cycle@riders.zaika.test", "vehicleType", "BICYCLE", "vehicleNumber", "")))
			.andExpect(redirectedUrl("/partner"));
		mvc.perform(signUp(Map.of("email", "cycle2@riders.zaika.test", "vehicleType", "BICYCLE", "vehicleNumber",
				"anything")))
			.andExpect(redirectedUrl("/partner"));
		assertThat(profileOf("cycle@riders.zaika.test").getVehicleNumber()).isNull();
		assertThat(profileOf("cycle2@riders.zaika.test").getVehicleNumber()).isNull();

		User cyclist = users.findByEmailIgnoreCase("cycle@riders.zaika.test").orElseThrow();
		assertThat(riderPage(cyclist)).contains("Bicycle", "Not needed for a bicycle");
	}

	@Test
	void missingDetailsShowInlineErrorsAndKeepWhatWasTyped() throws Exception {
		Map<String, String> bad = new LinkedHashMap<>();
		bad.put("name", "Rahul <b>Pawar</b>");
		bad.put("email", "not-an-email");
		bad.put("phone", "12345");
		bad.put("confirmPassword", "Different1");
		bad.put("vehicleType", "");
		bad.put("preferredArea", "");
		bad.put("acceptTerms", "false");
		String html = mvc.perform(signUp(bad))
			.andExpect(status().isOk())
			.andExpect(view().name("partner/onboarding"))
			.andExpect(model().attributeHasFieldErrors("form", "email", "phone", "confirmPassword", "vehicleType",
					"preferredArea", "acceptTerms"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("A few details need another look.",
				"Enter a valid email address, like asha@example.com.",
				"Enter a 10-digit mobile number starting with 6, 7, 8 or 9.", "The passwords don&#39;t match.",
				"Choose the vehicle you&#39;ll ride.", "Choose the area you&#39;d like to ride in.",
				"Please confirm and accept the partner terms to sign up.",
				"value=\"Rahul &lt;b&gt;Pawar&lt;/b&gt;\"", "value=\"MH09EK4521\"", "aria-invalid=\"true\"")
			.doesNotContain("value=\"Kolhapur2026\"", "<b>Pawar");
		assertThat(users.findByEmailIgnoreCase("not-an-email")).isEmpty();

		String blank = page(signUp(Map.of("name", " ", "email", "", "phone", "", "password", "", "confirmPassword", "")));
		assertThat(blank).contains("Tell us your name.", "Enter your email address.", "Enter your mobile number.",
				"Use 8 or more characters with at least one letter and one digit.");
	}

	@Test
	void motorVehiclesNeedARealNumberPlateAndTheAreaMustBeOnTheList() throws Exception {
		String missing = page(signUp(Map.of("email", "plate@riders.zaika.test", "vehicleType", "SCOOTER",
				"vehicleNumber", "  ")));
		assertThat(missing).contains("Enter your vehicle&#39;s number plate.");

		String wrong = page(signUp(Map.of("email", "plate@riders.zaika.test", "vehicleNumber", "1234 <x>")));
		assertThat(wrong).contains("Enter the number as it is on your plate, like MH 09 AB 1234.",
				"value=\"1234&lt;X&gt;\"", "id=\"vehicle-bike\" value=\"BIKE\" checked=\"checked\"");

		String area = page(signUp(Map.of("email", "plate@riders.zaika.test", "preferredArea", "Mumbai")));
		assertThat(area).contains("Choose an area from the list.");

		String tampered = page(signUp(Map.of("email", "plate@riders.zaika.test", "vehicleType", "JETPACK")));
		assertThat(tampered).contains("Choose a motorbike, scooter or bicycle.");

		assertThat(users.findByEmailIgnoreCase("plate@riders.zaika.test")).isEmpty();
	}

	@Test
	void anEmailWithAnAccountIsRefusedAndNothingIsSaved() throws Exception {
		User taken = users.save(new User("Riya Shinde", "taken@riders.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		long before = profiles.count();
		String html = mvc.perform(signUp(Map.of("email", "Taken@Riders.Zaika.Test")))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "email", "taken"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("This email already has a Zaika account. Use another email to ride with us.");
		assertThat(profiles.count()).isEqualTo(before);
		assertThat(users.findById(taken.getId()).orElseThrow().getRole()).isEqualTo(Role.CUSTOMER);
	}

	@Test
	void withoutCsrfTokenNothingIsCreated() throws Exception {
		MockHttpServletRequestBuilder request = post(FORM);
		validForm(Map.of("email", "csrf@riders.zaika.test")).forEach(request::param);
		mvc.perform(request).andExpect(status().isForbidden());
		assertThat(users.findByEmailIgnoreCase("csrf@riders.zaika.test")).isEmpty();
	}

	@Test
	void signedInVisitorsAreSentToTheirOwnArea() throws Exception {
		User customer = users.save(new User("Riya Shinde", "riya@riders.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		mvc.perform(get(FORM).with(user(userDetails.loadUserByUsername(customer.getEmail()))))
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("flashInfo", "You're signed in as riya@riders.zaika.test. Riding needs its "
					+ "own partner account: log out, then sign up as a delivery partner."));
		mvc.perform(signUp(Map.of("email", "second@riders.zaika.test"))
			.with(user(userDetails.loadUserByUsername(customer.getEmail()))))
			.andExpect(redirectedUrl("/"));
		assertThat(users.findByEmailIgnoreCase("second@riders.zaika.test")).isEmpty();

		Restaurant kitchen = fixtures.restaurant("rider-signup-kitchen", "Rider Signup Kitchen").save();
		mvc.perform(get(FORM).with(user(userDetails.loadUserByUsername(kitchen.getOwner().getEmail()))))
			.andExpect(redirectedUrl("/owner"));

		User rider = rider("already@riders.zaika.test", RiderStatus.APPROVED, null);
		mvc.perform(get(FORM).with(user(userDetails.loadUserByUsername(rider.getEmail()))))
			.andExpect(redirectedUrl("/partner"))
			.andExpect(flash().attributeCount(0));
	}

	// ----------------------------------------------------------------------- rider panel status banner

	@Test
	void suspendedRiderSeesTheReason() throws Exception {
		User rider = rider("suspended@riders.zaika.test", RiderStatus.SUSPENDED,
				"Three <b>late</b> deliveries this week.");
		assertThat(riderPage(rider)).contains("Suspended", "Your rider account is suspended.",
				"You can't go online or take deliveries right now.",
				"Reason: Three &lt;b&gt;late&lt;/b&gt; deliveries this week.")
			.doesNotContain("Waiting for review.", "<b>late");
	}

	@Test
	void approvedRiderHasNoBanner() throws Exception {
		User rider = rider("approved@riders.zaika.test", RiderStatus.APPROVED, null);
		assertThat(riderPage(rider)).contains("Approved", "Scooter", "MH09FG7310", "Based in Shahupuri")
			.doesNotContain("owner-status", "Waiting for review.", "is suspended");
	}

	@Test
	void eachRiderSeesOnlyTheirOwnProfile() throws Exception {
		User mine = rider("first@riders.zaika.test", RiderStatus.PENDING, null);
		User other = rider("second-rider@riders.zaika.test", RiderStatus.APPROVED, null);
		assertThat(riderPage(mine)).contains("first@riders.zaika.test").doesNotContain(other.getEmail());
	}

	@Test
	void riderAccountWithoutProfileSeesAnEmptyState() throws Exception {
		User lone = users.save(new User("Lone Rider", "lone@riders.zaika.test", "9876543210", "{noop}x",
				Role.DELIVERY_PARTNER));
		assertThat(riderPage(lone)).contains("No rider profile on this account", "href=\"/contact\"");
	}

	@Test
	void onlyRidersOpenTheRiderPanel() throws Exception {
		mvc.perform(get("/partner")).andExpect(redirectedUrl("/login"));
		User customer = users.save(new User("Customer", "cust@riders.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		mvc.perform(get("/partner").with(user(userDetails.loadUserByUsername(customer.getEmail()))))
			.andExpect(status().isForbidden());
		Restaurant kitchen = fixtures.restaurant("rider-panel-kitchen", "Rider Panel Kitchen").save();
		mvc.perform(get("/partner").with(user(userDetails.loadUserByUsername(kitchen.getOwner().getEmail()))))
			.andExpect(status().isForbidden());
	}

	// ----------------------------------------------------------------------- helpers

	private User rider(String email, RiderStatus status, String reason) {
		User rider = users.save(new User("Sagar More", email, "9890011222", "{noop}x", Role.DELIVERY_PARTNER));
		DeliveryPartnerProfile profile = new DeliveryPartnerProfile(rider, VehicleType.SCOOTER, "MH09FG7310",
				"Shahupuri");
		profile.changeStatus(status, reason);
		profiles.save(profile);
		return rider;
	}

	private DeliveryPartnerProfile profileOf(String email) {
		return profiles.findByUserId(users.findByEmailIgnoreCase(email).orElseThrow().getId()).orElseThrow();
	}

	private String riderPage(User rider) throws Exception {
		return page(get("/partner").with(user(userDetails.loadUserByUsername(rider.getEmail()))));
	}

	private String page(RequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	private MockHttpServletRequestBuilder signUp(Map<String, String> overrides) {
		MockHttpServletRequestBuilder request = post(FORM).with(csrf());
		validForm(overrides).forEach(request::param);
		return request;
	}

	private static Map<String, String> validForm(Map<String, String> overrides) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("name", "Rahul Pawar");
		form.put("phone", "9890011221");
		form.put("email", "rahul@riders.zaika.test");
		form.put("password", "Kolhapur2026");
		form.put("confirmPassword", "Kolhapur2026");
		form.put("vehicleType", "BIKE");
		form.put("vehicleNumber", "MH09EK4521");
		form.put("preferredArea", "Rajarampuri");
		form.put("acceptTerms", "true");
		form.putAll(overrides);
		return form;
	}

}
