package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.storage.StorageService;
import com.zaika.support.CatalogFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T28: "Partner with us", the restaurant sign-up form (owner account + PENDING restaurant, signed in straight away)
 * and the owner dashboard's status banner for pending, rejected and suspended restaurants.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = { "spring.datasource.url=jdbc:h2:mem:onboarding;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		// not the default 499, so the fee copy is proven to come from the setting
		"app.free-delivery-threshold=599.00" })
class RestaurantOnboardingTest {

	static final String FORM = "/partner-with-us/restaurant";

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13 };

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	StorageService storage;

	@MockitoSpyBean
	RestaurantOnboardingService onboarding;

	Cuisine kolhapuri;

	Cuisine thali;

	Cuisine biryani;

	Cuisine chinese;

	@BeforeEach
	void cuisines() {
		kolhapuri = fixtures.cuisine("Kolhapuri");
		thali = fixtures.cuisine("Thali");
		biryani = fixtures.cuisine("Biryani");
		chinese = fixtures.cuisine("Chinese");
	}

	// ----------------------------------------------------------------------- Partner with us

	@Test
	void partnerLandingPageHasBenefitsStepsAndBothCallsToAction() throws Exception {
		String html = page(get("/partner-with-us"));
		assertThat(html)
			.contains("<title>Partner with us | Zaika</title>", "Cook or ride with Kolhapur",
					"Why restaurants choose Zaika", "Why riders ride with us", "How joining works",
					"href=\"/partner-with-us/restaurant\">Register your restaurant</a>",
					"href=\"/partner-with-us/rider\">Become a delivery partner</a>", "mailto:partners@zaika.test")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
			.doesNotContainPattern("\\son[a-z]+=");
	}

	@Test
	void homeFooterAndLoginLinksToThePartnerPagesAnswer200() throws Exception {
		String home = page(get("/"));
		assertThat(home).contains("href=\"/partner-with-us/restaurant\"", "href=\"/partner-with-us\"");
		assertThat(page(get("/login"))).contains("href=\"/partner-with-us\"");
		mvc.perform(get("/partner-with-us")).andExpect(status().isOk());
		mvc.perform(get(FORM)).andExpect(status().isOk());
	}

	// ----------------------------------------------------------------------- the form

	@Test
	void signUpFormHasFourSectionsAndEveryField() throws Exception {
		String html = page(get(FORM));
		assertThat(html)
			.contains("<title>Register your restaurant | Zaika</title>",
					"enctype=\"multipart/form-data\"", "name=\"_csrf\"",
					"<span class=\"checkout-step-no\" aria-hidden=\"true\">1</span>About you</legend>",
					"<span class=\"checkout-step-no\" aria-hidden=\"true\">2</span>Your restaurant</legend>",
					"<span class=\"checkout-step-no\" aria-hidden=\"true\">3</span>Location and contact</legend>",
					"<span class=\"checkout-step-no\" aria-hidden=\"true\">4</span>Hours and fees</legend>",
					"id=\"ownerName\"", "id=\"ownerPhone\"", "id=\"email\"", "id=\"password\"", "id=\"confirmPassword\"",
					"id=\"restaurantName\"", "id=\"tagline\"", "id=\"description\"", "id=\"pureVeg\"",
					"type=\"file\" id=\"logo\" name=\"logo\" accept=\"image/jpeg,image/png,image/webp\"",
					"id=\"restaurantPhone\"", "id=\"address\"", "id=\"area\"", "id=\"pincode\"",
					"type=\"time\" id=\"opensAt\" name=\"opensAt\" value=\"11:00\"",
					"type=\"time\" id=\"closesAt\" name=\"closesAt\" value=\"23:00\"",
					"<option value=\"20\" selected=\"selected\">20 min</option>", "id=\"costForTwo\"",
					"id=\"minimumOrder\"", "id=\"deliveryFee\"", "Free above ₹599 for customers.",
					"id=\"acceptTerms\"", "Submit for review",
					"What happens next")
			.containsPattern("id=\"city\" name=\"city\" value=\"Kolhapur\"")
			.contains("name=\"cuisineIds\" id=\"cuisine-" + kolhapuri.getId() + "\" value=\"" + kolhapuri.getId() + "\"")
			.doesNotContain("role=\"alert\"", "aria-invalid")
			.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
			.doesNotContainPattern("\\son[a-z]+=");
	}

	@Test
	void validSignUpCreatesTheOwnerAndAPendingRestaurantAndSignsThemIn() throws Exception {
		MvcResult result = mvc.perform(signUp(Map.of("email", " Asha@Onboard.Zaika.Test ", "ownerPhone", "+91 98220 11223"))
			.file(new MockMultipartFile("logo", "logo.png", "image/png", PNG)))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/owner"))
			.andExpect(flash().attribute("flashSuccess",
					"Thanks, Asha! Tambda Tadka House is registered and waiting for review."))
			.andExpect(authenticated().withUsername("asha@onboard.zaika.test").withRoles("RESTAURANT_OWNER"))
			.andReturn();

		User owner = users.findByEmailIgnoreCase("asha@onboard.zaika.test").orElseThrow();
		assertThat(owner.getName()).isEqualTo("Asha Kulkarni");
		assertThat(owner.getPhone()).isEqualTo("9822011223");
		assertThat(owner.getRole()).isEqualTo(Role.RESTAURANT_OWNER);
		assertThat(passwordEncoder.matches("Kolhapur2026", owner.getPasswordHash())).isTrue();

		Restaurant r = restaurants.findWithCuisinesByOwnerId(owner.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(RestaurantStatus.PENDING);
		assertThat(r.getSlug()).isEqualTo("tambda-tadka-house");
		assertThat(r.getName()).isEqualTo("Tambda Tadka House");
		assertThat(r.getTagline()).isEqualTo("Fiery Kolhapuri rassa, every day");
		assertThat(r.getDescription()).isNull();
		assertThat(r.getCuisines()).extracting(Cuisine::getName).containsExactly("Kolhapuri", "Thali");
		assertThat(r.isPureVeg()).isFalse();
		assertThat(r.getPhone()).isEqualTo("9876500011");
		assertThat(r.getAddress()).isEqualTo("4, Laxmi Complex, Mahadwar Road");
		assertThat(r.getArea()).isEqualTo("Mahadwar Road");
		assertThat(r.getCity()).isEqualTo("Kolhapur");
		assertThat(r.getPincode()).isEqualTo("416012");
		assertThat(r.getOpensAt()).isEqualTo(LocalTime.of(18, 0));
		assertThat(r.getClosesAt()).isEqualTo(LocalTime.of(2, 0));
		assertThat(r.getAvgPrepMinutes()).isEqualTo(25);
		assertThat(r.getCostForTwo()).isEqualByComparingTo("450.00");
		assertThat(r.getMinimumOrder()).isEqualTo(new BigDecimal("150.00"));
		assertThat(r.getDeliveryFee()).isEqualTo(new BigDecimal("25.50"));
		assertThat(r.isAcceptingOrders()).isTrue();
		assertThat(r.getLogoPath()).matches("logo/[0-9a-f-]{36}\\.png");
		assertThat(storage.root().resolve(r.getLogoPath())).exists();

		// the session is signed in: the owner dashboard opens with the review banner, other areas do not
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession();
		String dashboard = mvc.perform(get("/owner").session(session).flashAttrs(result.getFlashMap()))
			.andExpect(status().isOk())
			.andExpect(view().name("owner/dashboard"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(dashboard).contains("Thanks, Asha! Tambda Tadka House is registered and waiting for review.",
				"<h1 class=\"owner-title\">Tambda Tadka House</h1>", "Under review", "Waiting for review.",
				"Customers can't see your restaurant until it's approved.", "Kolhapuri, Thali", "6:00 PM to 2:00 AM",
				"About 25 min", "₹450", "₹150", "₹25.50, free above ₹599", "+91 98765 00011",
				"src=\"/uploads/" + r.getLogoPath() + "\"")
			.doesNotContain("View your page");
		mvc.perform(get("/orders").session(session)).andExpect(status().isForbidden());

		// a pending restaurant stays out of customers' sight
		mvc.perform(get("/r/tambda-tadka-house")).andExpect(status().isNotFound());

		// and the new account signs in with the login form, landing on the dashboard
		mvc.perform(post("/login").param("email", "asha@onboard.zaika.test").param("password", "Kolhapur2026").with(csrf()))
			.andExpect(redirectedUrl("/owner"));
	}

	@Test
	void theThanksUsesTheFirstNameWhateverWhitespaceFollowsIt() throws Exception {
		mvc.perform(signUp(Map.of("email", "tab@onboard.zaika.test", "ownerName", "Asha\tKulkarni",
				"restaurantName", "Tab Kitchen")))
			.andExpect(flash().attribute("flashSuccess", "Thanks, Asha! Tab Kitchen is registered and waiting for review."));
	}

	@Test
	void aFailedRegistrationRemovesTheStoredLogo() throws Exception {
		long logos = storedLogos();
		doThrow(new IllegalStateException("slug taken meanwhile")).when(onboarding).register(any(), any());
		assertThatThrownBy(() -> mvc.perform(signUp(Map.of("email", "race@onboard.zaika.test"))
			.file(new MockMultipartFile("logo", "logo.png", "image/png", PNG))))
			.hasRootCauseMessage("slug taken meanwhile");
		assertThat(storedLogos()).isEqualTo(logos);
	}

	@Test
	void pureVegKitchenWithoutLogoUsesTheDefaults() throws Exception {
		mvc.perform(signUp(Map.of("email", "veg@onboard.zaika.test", "restaurantName", "Shree Krishna Bhojanalay",
				"pureVeg", "true", "tagline", "  ", "description", "  Thalis since 1985. ", "cuisineIds",
				String.valueOf(thali.getId()), "minimumOrder", "0", "deliveryFee", "0")))
			.andExpect(redirectedUrl("/owner"));
		Restaurant r = restaurants.findBySlug("shree-krishna-bhojanalay").orElseThrow();
		assertThat(r.isPureVeg()).isTrue();
		assertThat(r.getTagline()).isNull();
		assertThat(r.getDescription()).isEqualTo("Thalis since 1985.");
		assertThat(r.getLogoPath()).isNull();
		assertThat(r.getMinimumOrder()).isEqualTo(new BigDecimal("0.00"));
		assertThat(r.getDeliveryFee()).isEqualTo(new BigDecimal("0.00"));
	}

	@Test
	void takenSlugsGetTheAreaThenANumber() {
		fixtures.restaurant("misal-point", "Misal Point").save();
		assertThat(onboarding.uniqueSlug("Misal Point!", "Shahupuri")).isEqualTo("misal-point-shahupuri");
		fixtures.restaurant("misal-point-shahupuri", "Misal Point").save();
		assertThat(onboarding.uniqueSlug("Misal Point", "Shahupuri")).isEqualTo("misal-point-2");
		fixtures.restaurant("misal-point-2", "Misal Point").save();
		assertThat(onboarding.uniqueSlug("Misal Point", "Shahupuri")).isEqualTo("misal-point-3");
		assertThat(onboarding.uniqueSlug("Café Rankala", "Rankala")).isEqualTo("cafe-rankala");
		assertThat(onboarding.uniqueSlug("A".repeat(80), "Area")).hasSize(76);
	}

	@Test
	void missingDetailsShowInlineErrorsAndKeepWhatWasTyped() throws Exception {
		Map<String, String> bad = new LinkedHashMap<>();
		bad.put("ownerName", "");
		bad.put("email", "not-an-email");
		bad.put("ownerPhone", "12345");
		bad.put("confirmPassword", "Different1");
		bad.put("restaurantName", "Tambda <b>Tadka</b>");
		bad.put("restaurantPhone", "");
		bad.put("pincode", "4160");
		bad.put("cuisineIds", "none");
		bad.put("costForTwo", "abc");
		bad.put("minimumOrder", "-5");
		bad.put("deliveryFee", "25.555");
		bad.put("opensAt", "25:99");
		bad.put("avgPrepMinutes", "17");
		bad.put("acceptTerms", "false");
		String html = mvc.perform(signUp(bad))
			.andExpect(status().isOk())
			.andExpect(view().name("owner/onboarding"))
			.andExpect(model().attributeHasFieldErrors("form", "ownerName", "email", "ownerPhone", "confirmPassword",
					"restaurantPhone", "pincode", "cuisineIds", "costForTwo", "minimumOrder", "deliveryFee", "opensAt",
					"avgPrepMinutes", "acceptTerms"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(html).contains("A few details need another look.", "Tell us your name.",
				"Enter a valid email address, like asha@example.com.",
				"Enter a 10-digit mobile number starting with 6, 7, 8 or 9.", "The passwords don&#39;t match.",
				"Enter the number customers and riders should call.", "Enter a valid 6-digit PIN code, like 416008.",
				"Pick between 1 and 3 cuisines.", "Enter an amount in rupees, like 250.",
				"The minimum order cannot be negative.", "Use rupees and up to 2 decimals, like 30.",
				"Enter a time, like 11:00 AM.", "Choose a preparation time.",
				"Please accept the partner terms to register.",
				"value=\"Tambda &lt;b&gt;Tadka&lt;/b&gt;\"", "value=\"Mahadwar Road\"", "aria-invalid=\"true\"")
			.doesNotContain("value=\"Kolhapur2026\"");
		assertThat(users.findByEmailIgnoreCase("not-an-email")).isEmpty();
		assertThat(restaurants.findAll()).noneMatch(r -> r.getName().contains("<b>"));
	}

	@Test
	void cuisinesMustBeOneToThreeRealOnes() throws Exception {
		String four = kolhapuri.getId() + "," + thali.getId() + "," + biryani.getId() + "," + chinese.getId();
		mvc.perform(signUp(Map.of("email", "four@onboard.zaika.test", "cuisineIds", four)))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "cuisineIds", "Size"));
		mvc.perform(signUp(Map.of("email", "fake@onboard.zaika.test", "cuisineIds", "999999")))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "cuisineIds", "unknown"));
		String kept = page(signUp(Map.of("email", "four@onboard.zaika.test", "cuisineIds", four)));
		assertThat(kept).contains("value=\"" + chinese.getId() + "\" checked=\"checked\"");
		assertThat(users.findByEmailIgnoreCase("four@onboard.zaika.test")).isEmpty();
		assertThat(users.findByEmailIgnoreCase("fake@onboard.zaika.test")).isEmpty();
	}

	@Test
	void anEmailWithAnAccountIsRefusedAndNothingIsSaved() throws Exception {
		users.save(new User("Riya Shinde", "taken@onboard.zaika.test", "9876543210", "{noop}x", Role.CUSTOMER));
		long logos = storedLogos();
		String html = mvc.perform(signUp(Map.of("email", "Taken@Onboard.Zaika.Test", "restaurantName", "Taken Kitchen"))
			.file(new MockMultipartFile("logo", "logo.png", "image/png", PNG)))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "email", "taken"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("This email already has a Zaika account. Use another email for your restaurant.",
				"Attach your logo again too.");
		assertThat(restaurants.findAll()).noneMatch(r -> r.getName().equals("Taken Kitchen"));
		assertThat(users.findByEmailIgnoreCase("taken@onboard.zaika.test").orElseThrow().getRole())
			.isEqualTo(Role.CUSTOMER);
		assertThat(storedLogos()).isEqualTo(logos);
	}

	@Test
	void aLogoThatIsNotAnImageGetsAnInlineError() throws Exception {
		String html = mvc.perform(signUp(Map.of("email", "logo@onboard.zaika.test"))
			.file(new MockMultipartFile("logo", "menu.pdf", "image/png", "%PDF-1.7".getBytes())))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "logo", "invalid"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Choose a JPG, PNG or WebP image.", "id=\"logo-error\"");
		assertThat(users.findByEmailIgnoreCase("logo@onboard.zaika.test")).isEmpty();

		byte[] big = new byte[(int) StorageService.MAX_BYTES + 1];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		mvc.perform(signUp(Map.of("email", "logo@onboard.zaika.test"))
			.file(new MockMultipartFile("logo", "big.png", "image/png", big)))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("form", "logo", "invalid"));
		assertThat(users.findByEmailIgnoreCase("logo@onboard.zaika.test")).isEmpty();
	}

	@Test
	void withoutCsrfTokenNothingIsCreated() throws Exception {
		mvc.perform(multipart(FORM).params(toParams(validForm(Map.of("email", "csrf@onboard.zaika.test")))))
			.andExpect(status().isForbidden());
		assertThat(users.findByEmailIgnoreCase("csrf@onboard.zaika.test")).isEmpty();
	}

	@Test
	void signedInVisitorsAreSentToTheirOwnArea() throws Exception {
		User customer = users.save(new User("Riya Shinde", "riya@onboard.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		mvc.perform(get(FORM).with(user(userDetails.loadUserByUsername(customer.getEmail()))))
			.andExpect(redirectedUrl("/"))
			.andExpect(flash().attribute("flashInfo", "You're signed in as riya@onboard.zaika.test. A restaurant needs "
					+ "its own partner account: log out, then register your restaurant."));
		mvc.perform(signUp(Map.of("email", "second@onboard.zaika.test"))
			.with(user(userDetails.loadUserByUsername(customer.getEmail()))))
			.andExpect(redirectedUrl("/"));
		assertThat(users.findByEmailIgnoreCase("second@onboard.zaika.test")).isEmpty();

		Restaurant mine = fixtures.restaurant("signed-in-kitchen", "Signed In Kitchen").save();
		mvc.perform(get(FORM).with(user(userDetails.loadUserByUsername(mine.getOwner().getEmail()))))
			.andExpect(redirectedUrl("/owner"))
			.andExpect(flash().attributeCount(0));
	}

	// ----------------------------------------------------------------------- owner dashboard status banner

	@Test
	void rejectedRestaurantShowsTheReason() throws Exception {
		Restaurant r = withStatus(fixtures.restaurant("rejected-kitchen", "Rejected Kitchen").save(),
				RestaurantStatus.REJECTED, "The FSSAI licence <b>does not match</b> the restaurant name.");
		String html = ownerPage(r);
		assertThat(html).contains("Not approved", "We couldn't approve Rejected Kitchen.",
				"Reason: The FSSAI licence &lt;b&gt;does not match&lt;/b&gt; the restaurant name.")
			.doesNotContain("Waiting for review.", "View your page", "<b>does");
	}

	@Test
	void suspendedRestaurantShowsTheReason() throws Exception {
		Restaurant r = withStatus(fixtures.restaurant("suspended-kitchen", "Suspended Kitchen").save(),
				RestaurantStatus.SUSPENDED, "Repeated late deliveries this week.");
		assertThat(ownerPage(r)).contains("Suspended Kitchen is suspended.",
				"Customers can't see or order from it right now.", "Reason: Repeated late deliveries this week.")
			.doesNotContain("We couldn't approve", "View your page");
	}

	@Test
	void approvedRestaurantHasNoBannerAndLinksToItsPage() throws Exception {
		Restaurant r = fixtures.restaurant("live-kitchen", "Live Kitchen").cuisines("Kolhapuri").save();
		assertThat(ownerPage(r)).contains("Live on Zaika", "href=\"/r/live-kitchen\">View your page</a>")
			.doesNotContain("owner-status", "Waiting for review.", "We couldn't approve", "is suspended");
	}

	@Test
	void eachOwnerSeesOnlyTheirOwnRestaurant() throws Exception {
		Restaurant mine = fixtures.restaurant("first-owner-kitchen", "First Owner Kitchen").save();
		fixtures.restaurant("second-owner-kitchen", "Second Owner Kitchen").save();
		assertThat(ownerPage(mine)).contains("First Owner Kitchen").doesNotContain("Second Owner Kitchen");
	}

	@Test
	void ownerWithoutRestaurantSeesAnEmptyState() throws Exception {
		User owner = users.save(new User("Lone Owner", "lone@onboard.zaika.test", "9876543210", "{noop}x",
				Role.RESTAURANT_OWNER));
		String html = page(get("/owner").with(user(userDetails.loadUserByUsername(owner.getEmail()))));
		assertThat(html).contains("No restaurant on this account", "href=\"/contact\"");
	}

	@Test
	void onlyOwnersOpenTheDashboard() throws Exception {
		mvc.perform(get("/owner")).andExpect(redirectedUrl("/login"));
		User customer = users.save(new User("Customer", "cust@onboard.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		mvc.perform(get("/owner").with(user(userDetails.loadUserByUsername(customer.getEmail()))))
			.andExpect(status().isForbidden());
	}

	// ----------------------------------------------------------------------- helpers

	private String ownerPage(Restaurant r) throws Exception {
		return page(get("/owner").with(user(userDetails.loadUserByUsername(r.getOwner().getEmail()))));
	}

	private Restaurant withStatus(Restaurant r, RestaurantStatus status, String reason) {
		Restaurant loaded = restaurants.findById(r.getId()).orElseThrow();
		loaded.changeStatus(status, reason);
		restaurants.save(loaded);
		return r; // its owner is loaded, unlike the copy's

	}

	private String page(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	private MockMultipartHttpServletRequestBuilder signUp(Map<String, String> overrides) {
		MockMultipartHttpServletRequestBuilder request = multipart(FORM);
		request.params(toParams(validForm(overrides))).with(csrf());
		return request;
	}

	private static Map<String, String> validForm(Map<String, String> overrides) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("ownerName", "Asha Kulkarni");
		form.put("email", "asha@onboard.zaika.test");
		form.put("ownerPhone", "9822011223");
		form.put("password", "Kolhapur2026");
		form.put("confirmPassword", "Kolhapur2026");
		form.put("restaurantName", "Tambda Tadka House");
		form.put("tagline", "Fiery Kolhapuri rassa, every day");
		form.put("description", "");
		form.put("restaurantPhone", "98765 00011");
		form.put("address", "4, Laxmi Complex, Mahadwar Road");
		form.put("area", "Mahadwar Road");
		form.put("city", "Kolhapur");
		form.put("pincode", "416 012");
		form.put("cuisineIds", "");
		form.put("opensAt", "18:00");
		form.put("closesAt", "02:00");
		form.put("avgPrepMinutes", "25");
		form.put("costForTwo", "450");
		form.put("minimumOrder", "150");
		form.put("deliveryFee", "25.50");
		form.put("acceptTerms", "true");
		form.putAll(overrides);
		return form;
	}

	/**
	 * {@code cuisineIds} holds comma-separated ids: empty means the two defaults (Kolhapuri, Thali), {@code none}
	 * sends no cuisine at all.
	 */
	private MultiValueMap<String, String> toParams(Map<String, String> form) {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		form.forEach(params::add);
		String ids = params.remove("cuisineIds").get(0);
		if (ids.isEmpty()) {
			params.addAll("cuisineIds", List.of(String.valueOf(kolhapuri.getId()), String.valueOf(thali.getId())));
		}
		else if (!ids.equals("none")) {
			params.addAll("cuisineIds", List.of(ids.split(",")));
		}
		return params;
	}

	private long storedLogos() throws IOException {
		Path dir = storage.root().resolve("logo");
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.count();
		}
	}

}
