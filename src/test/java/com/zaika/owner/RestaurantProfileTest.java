package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
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
 * T31: the owner's restaurant profile at {@code /owner/restaurant}: details, cuisines, pure veg (refused while non-veg
 * dishes are on the menu), hours and fees, logo and cover upload with preview, validation with input kept, and that an
 * owner only ever edits their own restaurant.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ownerprofile;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RestaurantProfileTest {

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13 };

	static final byte[] JPG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16 };

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	StorageService storage;

	Restaurant kitchen(CatalogFixtures.Builder builder) {
		return builder.cuisines("Kolhapuri").save();
	}

	Restaurant kitchen() {
		return kitchen(fixtures.restaurant(slug(), "Profile Kitchen"));
	}

	static String slug() {
		return "profile-" + UUID.randomUUID().toString().substring(0, 8);
	}

	RequestPostProcessor ownerOf(Restaurant r) {
		return user(userDetails.loadUserByUsername(r.getOwner().getEmail()));
	}

	Restaurant reload(Restaurant r) {
		return restaurants.findWithCuisinesByOwnerId(r.getOwner().getId()).orElseThrow();
	}

	/** A complete, valid profile form; {@code changes} replace fields (a null value leaves the field out). */
	MockMultipartHttpServletRequestBuilder save(Map<String, String> changes, MockMultipartFile... files) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("restaurantName", "Tambda Rassa House");
		fields.put("tagline", "Mutton rassa the Kolhapur way");
		fields.put("description", "Family kitchen in Shivaji Peth since 2004.");
		fields.put("restaurantPhone", "98220 11223");
		fields.put("address", "4, Shivaji Peth Road");
		fields.put("area", "Shivaji Peth");
		fields.put("city", "Kolhapur");
		fields.put("pincode", "416 012");
		fields.put("opensAt", "10:30");
		fields.put("closesAt", "23:30");
		fields.put("avgPrepMinutes", "25");
		fields.put("costForTwo", "550");
		fields.put("minimumOrder", "149");
		fields.put("deliveryFee", "35");
		fields.putAll(changes);
		MockMultipartHttpServletRequestBuilder request = multipart("/owner/restaurant");
		fields.forEach((name, value) -> {
			if (value != null) {
				request.param(name, value);
			}
		});
		if (!changes.containsKey("cuisineIds")) {
			request.param("cuisineIds", fixtures.cuisine("Kolhapuri").getId().toString(),
					fixtures.cuisine("Thali").getId().toString());
		}
		for (MockMultipartFile file : files) {
			request.file(file);
		}
		request.with(csrf());
		return request;
	}

	MockMultipartHttpServletRequestBuilder save() {
		return save(Map.of());
	}

	static MockMultipartFile image(String field, byte[] bytes) {
		return new MockMultipartFile(field, field + ".png", "image/png", bytes);
	}

	long filesIn(String folder) throws IOException {
		Path dir = storage.root().resolve(folder);
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.count();
		}
	}

	static String squash(String html) {
		return html.replaceAll("\\s+", " ");
	}

	// ---------------------------------------------------------------- the page

	@Test
	void thePageShowsTheRestaurantAsSavedInAMultipartFormToItself() throws Exception {
		Restaurant r = kitchen(fixtures.restaurant(slug(), "Profile Kitchen").prep(30).costForTwo(450).minimumOrder(99)
			.deliveryFee(25).hours("09:00", "22:00"));
		r.setTagline("Best misal in town");
		restaurants.save(r);
		String html = squash(mvc.perform(get("/owner/restaurant").with(ownerOf(r)))
			.andExpect(status().isOk())
			.andExpect(view().name("owner/restaurant"))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("<title>Restaurant profile | Zaika</title>")
			.contains("action=\"/owner/restaurant\" method=\"post\" enctype=\"multipart/form-data\"")
			.contains("name=\"_csrf\"")
			.contains("href=\"/owner/restaurant\" aria-current=\"page\"")
			.contains("value=\"Profile Kitchen\"", "value=\"Best misal in town\"", "value=\"9876543210\"",
					"value=\"12, Main Road\"", "value=\"Rajarampuri\"", "value=\"416008\"", "value=\"09:00\"",
					"value=\"22:00\"", "value=\"450.00\"", "value=\"99.00\"", "value=\"25.00\"")
			.contains("<option value=\"30\" selected=\"selected\">30 min</option>")
			.containsPattern("value=\"" + fixtures.cuisine("Kolhapuri").getId() + "\" checked=\"checked\"")
			.contains("Your page link stays /r/" + r.getSlug() + ", even if you change the name.")
			.contains("href=\"/r/" + r.getSlug() + "\">View your page</a>")
			.doesNotContain("Waiting for review")
			// previews: the placeholders for now, with the live preview hooks and script
			.contains("id=\"cover-preview\" src=\"/placeholder/restaurant/" + r.getSlug() + ".svg\"")
			.contains("id=\"logo-preview\" src=\"/placeholder/logo/" + r.getSlug() + ".svg\"")
			.contains("data-image-preview=\"cover-preview\"", "data-image-preview=\"logo-preview\"")
			.contains("id=\"cover-preview-note\" hidden", "src=\"/js/image-preview.js\"")
			.contains("No cover photo yet.", "No logo yet.")
			.doesNotContain("name=\"removeCover\"", "name=\"removeLogo\"");
		assertThat(html).doesNotContainPattern("id=\"pureVeg\"[^>]*checked");
	}

	@Test
	void savingUpdatesEveryDetailButKeepsTheSlug() throws Exception {
		Restaurant r = kitchen();
		mvc.perform(save().with(ownerOf(r)))
			.andExpect(redirectedUrl("/owner/restaurant"))
			.andExpect(flash().attribute("flashSuccess", "Profile saved. Customers see the changes right away."));
		Restaurant saved = reload(r);
		assertThat(saved.getSlug()).isEqualTo(r.getSlug());
		assertThat(saved.getName()).isEqualTo("Tambda Rassa House");
		assertThat(saved.getTagline()).isEqualTo("Mutton rassa the Kolhapur way");
		assertThat(saved.getDescription()).isEqualTo("Family kitchen in Shivaji Peth since 2004.");
		assertThat(saved.getCuisines()).extracting("name").containsExactly("Kolhapuri", "Thali");
		assertThat(saved.getPhone()).isEqualTo("9822011223");
		assertThat(saved.getAddress()).isEqualTo("4, Shivaji Peth Road");
		assertThat(saved.getArea()).isEqualTo("Shivaji Peth");
		assertThat(saved.getPincode()).isEqualTo("416012");
		assertThat(saved.getOpensAt()).isEqualTo(LocalTime.of(10, 30));
		assertThat(saved.getClosesAt()).isEqualTo(LocalTime.of(23, 30));
		assertThat(saved.getAvgPrepMinutes()).isEqualTo(25);
		assertThat(saved.getCostForTwo()).isEqualByComparingTo("550").hasToString("550.00");
		assertThat(saved.getMinimumOrder()).hasToString("149.00");
		assertThat(saved.getDeliveryFee()).hasToString("35.00");
		assertThat(saved.isPureVeg()).isFalse();

		String page = mvc.perform(get("/r/{slug}", r.getSlug())).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(page).contains("Tambda Rassa House", "Mutton rassa the Kolhapur way", "Shivaji Peth");
		assertThat(squash(mvc.perform(get("/owner/restaurant").with(ownerOf(r)))
			.andReturn().getResponse().getContentAsString()))
			.contains("value=\"Tambda Rassa House\"", "<option value=\"25\" selected=\"selected\">25 min</option>");
	}

	@Test
	void blankOptionalTextIsCleared() throws Exception {
		Restaurant r = kitchen();
		r.setTagline("Old tagline");
		r.setDescription("Old description");
		restaurants.save(r);
		mvc.perform(save(Map.of("tagline", " ", "description", "")).with(ownerOf(r)))
			.andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).getTagline()).isNull();
		assertThat(reload(r).getDescription()).isNull();
	}

	@Test
	void aPendingRestaurantKeepsItsBannerAndIsToldWhenChangesShow() throws Exception {
		Restaurant r = kitchen(fixtures.restaurant(slug(), "Pending Kitchen").status(RestaurantStatus.PENDING));
		String html = mvc.perform(get("/owner/restaurant").with(ownerOf(r))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Waiting for review").doesNotContain("View your page");
		mvc.perform(save().with(ownerOf(r)))
			.andExpect(flash().attribute("flashSuccess",
					"Profile saved. Customers will see it once your restaurant is approved."));
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.PENDING);
	}

	// ---------------------------------------------------------------- logo and cover

	@Test
	void logoAndCoverAreUploadedReplacedAndRemoved() throws Exception {
		Restaurant r = kitchen();
		mvc.perform(save(Map.of(), image("logo", PNG), image("cover", JPG)).with(ownerOf(r)))
			.andExpect(redirectedUrl("/owner/restaurant"));
		Restaurant saved = reload(r);
		assertThat(saved.getLogoPath()).startsWith("logo/").endsWith(".png");
		assertThat(saved.getCoverPath()).startsWith("cover/").endsWith(".jpg");
		Path firstLogo = storage.root().resolve(saved.getLogoPath());
		Path cover = storage.root().resolve(saved.getCoverPath());
		assertThat(firstLogo).exists();
		assertThat(cover).exists();

		String html = squash(mvc.perform(get("/owner/restaurant").with(ownerOf(r)))
			.andReturn().getResponse().getContentAsString());
		assertThat(html)
			.contains("id=\"cover-preview\" src=\"/uploads/" + saved.getCoverPath() + "\"")
			.contains("id=\"logo-preview\" src=\"/uploads/" + saved.getLogoPath() + "\"")
			.contains("Your cover photo", "Your logo", "name=\"removeCover\"", "name=\"removeLogo\"");
		assertThat(mvc.perform(get("/r/{slug}", r.getSlug())).andReturn().getResponse().getContentAsString())
			.contains("/uploads/" + saved.getCoverPath(), "/uploads/" + saved.getLogoPath());

		// a new logo replaces the old file; the cover stays
		mvc.perform(save(Map.of(), image("logo", PNG)).with(ownerOf(r))).andExpect(redirectedUrl("/owner/restaurant"));
		Restaurant replaced = reload(r);
		assertThat(replaced.getLogoPath()).isNotEqualTo(saved.getLogoPath());
		assertThat(storage.root().resolve(replaced.getLogoPath())).exists();
		assertThat(firstLogo).doesNotExist();
		assertThat(replaced.getCoverPath()).isEqualTo(saved.getCoverPath());

		// removing the cover clears it and deletes the file
		mvc.perform(save(Map.of("removeCover", "true")).with(ownerOf(r))).andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).getCoverPath()).isNull();
		assertThat(cover).doesNotExist();
		assertThat(reload(r).getLogoPath()).isEqualTo(replaced.getLogoPath());
	}

	@Test
	void aNewPhotoWinsOverItsRemoveTick() throws Exception {
		Restaurant r = kitchen();
		mvc.perform(save(Map.of("removeLogo", "true"), image("logo", PNG)).with(ownerOf(r)))
			.andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).getLogoPath()).startsWith("logo/");
	}

	@Test
	void anUnusablePhotoSavesNothingAndLeavesNoFileBehind() throws Exception {
		Restaurant r = kitchen();
		long logos = filesIn("logo");
		long covers = filesIn("cover");
		String html = mvc.perform(save(Map.of("restaurantName", "Renamed Kitchen"), image("logo", PNG),
				new MockMultipartFile("cover", "cover.gif", "image/gif", "GIF89a....".getBytes()))
				.with(ownerOf(r)))
			.andExpect(status().isOk())
			.andExpect(view().name("owner/restaurant"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("A few details need another look.", "Attach your photos again too.",
				"id=\"cover-error\"", "Choose a JPG, PNG or WebP image.", "value=\"Renamed Kitchen\"");
		Restaurant saved = reload(r);
		assertThat(saved.getName()).isEqualTo("Profile Kitchen");
		assertThat(saved.getLogoPath()).isNull();
		assertThat(filesIn("logo")).isEqualTo(logos); // the stored logo went again with the rollback
		assertThat(filesIn("cover")).isEqualTo(covers);
	}

	@Test
	void anOversizedLogoIsRefused() throws Exception {
		Restaurant r = kitchen();
		byte[] big = new byte[(int) StorageService.MAX_BYTES + 1];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		mvc.perform(save(Map.of(), image("logo", big)).with(ownerOf(r)))
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.contains("id=\"logo-error\"", "That image is over 2 MB."));
		assertThat(reload(r).getLogoPath()).isNull();
	}

	// ---------------------------------------------------------------- validation

	@Test
	void everyFieldIsValidatedAndTheTypedValuesAreKept() throws Exception {
		Restaurant r = kitchen();
		Map<String, String> bad = new LinkedHashMap<>();
		bad.put("restaurantName", " ");
		bad.put("tagline", "<b>Bold</b> " + "x".repeat(120));
		bad.put("restaurantPhone", "12345");
		bad.put("address", "");
		bad.put("area", "");
		bad.put("city", "");
		bad.put("pincode", "4160");
		bad.put("opensAt", "");
		bad.put("costForTwo", "10");
		bad.put("minimumOrder", "-5");
		bad.put("deliveryFee", "abc");
		bad.put("cuisineIds", null);
		String html = mvc.perform(save(bad).with(ownerOf(r)))
			.andExpect(status().isOk())
			.andExpect(view().name("owner/restaurant"))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Enter your restaurant&#39;s name.", "Keep the tagline under 120 characters.",
				"Enter a 10-digit mobile number starting with 6, 7, 8 or 9.", "Enter the shop number, building and road.",
				"Enter the area, like Rajarampuri.", "Enter the city.", "Enter a valid 6-digit PIN code, like 416008.",
				"Enter your opening time.", "Cost for two should be at least ₹50.",
				"The minimum order cannot be negative.", "Pick between 1 and 3 cuisines.")
			.contains("value=\"12345\"", "value=\"4160\"", "value=\"10\"")
			.contains("&lt;b&gt;Bold&lt;/b&gt;").doesNotContain("<b>Bold</b>");
		assertThat(reload(r).getName()).isEqualTo("Profile Kitchen");
	}

	@Test
	void moreThanThreeCuisinesAreRefused() throws Exception {
		Restaurant r = kitchen();
		Map<String, String> noCuisines = new LinkedHashMap<>();
		noCuisines.put("cuisineIds", null);
		MockMultipartHttpServletRequestBuilder request = save(noCuisines);
		for (String name : List.of("Kolhapuri", "Thali", "Biryani", "Chinese")) {
			request.param("cuisineIds", fixtures.cuisine(name).getId().toString());
		}
		String html = mvc.perform(request.with(ownerOf(r))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("Pick between 1 and 3 cuisines.");
		assertThat(reload(r).getCuisines()).extracting("name").containsExactly("Kolhapuri");
	}

	@Test
	void anUnknownCuisineOrPrepTimeIsRefused() throws Exception {
		Restaurant r = kitchen();
		String html = mvc.perform(save(Map.of("cuisineIds", "999999")).with(ownerOf(r))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("id=\"cuisineIds-error\"", "Pick your cuisines from the list.");
		html = mvc.perform(save(Map.of("avgPrepMinutes", "35")).with(ownerOf(r))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("id=\"avgPrepMinutes-error\"", "Choose a preparation time.");
		assertThat(reload(r).getAvgPrepMinutes()).isEqualTo(20);
	}

	@Test
	void aPrepTimeOutsideTheUsualListIsOfferedAndKept() throws Exception {
		Restaurant r = kitchen(fixtures.restaurant(slug(), "Slow Kitchen").prep(35));
		assertThat(mvc.perform(get("/owner/restaurant").with(ownerOf(r))).andReturn().getResponse().getContentAsString())
			.contains("<option value=\"35\" selected=\"selected\">35 min</option>");
		mvc.perform(save(Map.of("avgPrepMinutes", "35")).with(ownerOf(r))).andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).getAvgPrepMinutes()).isEqualTo(35);
	}

	// ---------------------------------------------------------------- pure veg

	@Test
	void pureVegIsRefusedWhileNonVegDishesAreOnTheMenu() throws Exception {
		Restaurant r = kitchen(fixtures.restaurant(slug(), "Mixed Kitchen").dishes("Anda Curry", "Mutton Sukka",
				"Chicken Kolhapuri").archivedDishes("Old Kheema"));
		String html = mvc.perform(save(Map.of("pureVeg", "true", "restaurantName", "Green Kitchen")).with(ownerOf(r)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("id=\"pureVeg-error\"",
				"Your menu still has 3 non-veg dishes: Anda Curry, Mutton Sukka and 1 more. Delete them or mark them veg, then switch to pure veg.")
			.containsPattern("id=\"pureVeg\"[^>]*checked")
			.contains("value=\"Green Kitchen\"");
		assertThat(reload(r).isPureVeg()).isFalse();
		assertThat(reload(r).getName()).isEqualTo("Mixed Kitchen");

		// sold-out non-veg dishes still count; archived ones do not
		List<MenuItem> dishes = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(r.getId());
		dishes.get(0).setArchived(true);
		dishes.get(1).setVeg(true);
		dishes.get(2).setAvailable(false);
		items.saveAll(dishes);
		html = mvc.perform(save(Map.of("pureVeg", "true")).with(ownerOf(r))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains(
				"Your menu still has a non-veg dish, Chicken Kolhapuri. Delete it or mark it veg, then switch to pure veg.");

		MenuItem chicken = items.findById(dishes.get(2).getId()).orElseThrow();
		chicken.setArchived(true);
		items.save(chicken);
		mvc.perform(save(Map.of("pureVeg", "true")).with(ownerOf(r))).andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).isPureVeg()).isTrue();
		assertThat(mvc.perform(get("/r/{slug}", r.getSlug())).andReturn().getResponse().getContentAsString())
			.contains("Pure veg kitchen: every dish is vegetarian");
	}

	@Test
	void twoNonVegDishesAreBothNamed() throws Exception {
		Restaurant r = kitchen(fixtures.restaurant(slug(), "Two Kitchen").dishes("Anda Curry", "Mutton Sukka"));
		assertThat(mvc.perform(save(Map.of("pureVeg", "true")).with(ownerOf(r))).andReturn().getResponse()
			.getContentAsString())
			.contains("Your menu still has 2 non-veg dishes: Anda Curry and Mutton Sukka. Delete them or mark them veg");
	}

	@Test
	void aPureVegKitchenCanSwitchBack() throws Exception {
		Restaurant r = kitchen(fixtures.restaurant(slug(), "Veg Kitchen").pureVeg().dishes("Misal Pav"));
		assertThat(mvc.perform(get("/owner/restaurant").with(ownerOf(r))).andReturn().getResponse().getContentAsString())
			.containsPattern("id=\"pureVeg\"[^>]*checked");
		mvc.perform(save(Map.of("pureVeg", "true")).with(ownerOf(r))).andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).isPureVeg()).isTrue();
		mvc.perform(save().with(ownerOf(r))).andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(r).isPureVeg()).isFalse();
	}

	// ---------------------------------------------------------------- links

	@Test
	void theDashboardAndMenuLinkToTheProfile() throws Exception {
		Restaurant r = kitchen();
		String dashboard = squash(mvc.perform(get("/owner").with(ownerOf(r))).andReturn().getResponse()
			.getContentAsString());
		assertThat(dashboard).contains("href=\"/owner/restaurant\"><svg", "restaurant profile</a>",
				"Restaurant profile</a>");
		assertThat(mvc.perform(get("/owner/menu").with(ownerOf(r))).andReturn().getResponse().getContentAsString())
			.contains("href=\"/owner/restaurant\"");
	}

	// ---------------------------------------------------------------- ownership and access

	@Test
	void anOwnerOnlyEverChangesTheirOwnRestaurant() throws Exception {
		Restaurant mine = kitchen();
		Restaurant theirs = kitchen(fixtures.restaurant(slug(), "Their Kitchen"));
		mvc.perform(save().param("id", theirs.getId().toString()).param("slug", theirs.getSlug()).with(ownerOf(mine)))
			.andExpect(redirectedUrl("/owner/restaurant"));
		assertThat(reload(mine).getName()).isEqualTo("Tambda Rassa House");
		Restaurant untouched = reload(theirs);
		assertThat(untouched.getName()).isEqualTo("Their Kitchen");
		assertThat(untouched.getAvgPrepMinutes()).isEqualTo(20);
		assertThat(mvc.perform(get("/owner/restaurant").with(ownerOf(theirs))).andReturn().getResponse()
			.getContentAsString()).contains("value=\"Their Kitchen\"");
	}

	@Test
	void anOwnerWithoutARestaurantGetsNotFound() throws Exception {
		User lone = users.save(new User("Lone Owner", slug() + "@ownerprofile.zaika.test", "9876543210", "{noop}x",
				Role.RESTAURANT_OWNER));
		RequestPostProcessor me = user(userDetails.loadUserByUsername(lone.getEmail()));
		mvc.perform(get("/owner/restaurant").with(me)).andExpect(status().isNotFound());
		mvc.perform(save().with(me)).andExpect(status().isNotFound());
	}

	@Test
	void onlySignedInOwnersWithACsrfTokenGetIn() throws Exception {
		Restaurant r = kitchen();
		mvc.perform(get("/owner/restaurant")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/owner/restaurant").param("restaurantName", "Hacked").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		User customer = users.save(new User("Customer", slug() + "@ownerprofile.zaika.test", "9876543210", "{noop}x",
				Role.CUSTOMER));
		RequestPostProcessor asCustomer = user(userDetails.loadUserByUsername(customer.getEmail()));
		mvc.perform(get("/owner/restaurant").with(asCustomer)).andExpect(status().isForbidden());
		mvc.perform(save().with(asCustomer)).andExpect(status().isForbidden());
		mvc.perform(multipart("/owner/restaurant").param("restaurantName", "Hacked").with(ownerOf(r)))
			.andExpect(status().isForbidden());
		assertThat(reload(r).getName()).isEqualTo("Profile Kitchen");
	}

	@Test
	void theMoneyStaysAtTwoDecimals() throws Exception {
		Restaurant r = kitchen();
		mvc.perform(save(Map.of("costForTwo", "399.5", "deliveryFee", "0", "minimumOrder", "0")).with(ownerOf(r)))
			.andExpect(redirectedUrl("/owner/restaurant"));
		Restaurant saved = reload(r);
		assertThat(saved.getCostForTwo()).isEqualTo(new BigDecimal("399.50"));
		assertThat(saved.getDeliveryFee()).isEqualTo(new BigDecimal("0.00"));
		assertThat(saved.getMinimumOrder()).isEqualTo(new BigDecimal("0.00"));
	}

}
