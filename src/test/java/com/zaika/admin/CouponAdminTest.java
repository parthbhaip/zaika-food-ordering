package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.Restaurant;
import com.zaika.coupon.Coupon;
import com.zaika.coupon.CouponRepository;
import com.zaika.coupon.CouponService;
import com.zaika.coupon.CouponService.CouponOffer;
import com.zaika.coupon.CouponType;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T41: coupons in the admin panel ({@code /admin/coupons}). The tests share one database and the list is
 * platform-wide, so every coupon gets a fresh code. Uses are written straight into {@code coupon_redemptions} (as
 * checkout would) to give coupons a usage count.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:admincoupons;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CouponAdminTest {

	static final LocalDateTime NOW = MutableClock.DEFAULT_NOW;

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	CouponRepository coupons;

	@Autowired
	CouponService couponService;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static String code() {
		return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
	}

	Coupon coupon(CouponType type, String value, Consumer<Coupon> setup) {
		Coupon c = new Coupon(code(), "A test offer.", type, new BigDecimal(value), NOW.minusDays(1));
		setup.accept(c);
		return coupons.save(c);
	}

	Restaurant restaurant(String name) {
		return fixtures.restaurant("cpn-" + UUID.randomUUID().toString().substring(0, 8), name).dishes("Thali").save();
	}

	/** {@code n} uses of the coupon by one new customer, {@code discount} each, as checkout records them. */
	void used(Coupon c, int n, String discount) {
		long customer = orders.customer().getId();
		for (int i = 0; i < n; i++) {
			jdbc.update("""
					INSERT INTO coupon_redemptions (coupon_id, user_id, discount, created_at, updated_at, version)
					VALUES (?, ?, ?, ?, ?, 0)""", c.getId(), customer, new BigDecimal(discount), NOW, NOW);
		}
	}

	User admin() {
		return users.save(new User("Aditi Kulkarni", "admin-" + UUID.randomUUID() + "@zaika.test", "9890011223",
				"hash", Role.ADMIN));
	}

	RequestPostProcessor as(User user) {
		return user(userDetails.loadUserByUsername(user.getEmail()));
	}

	RequestPostProcessor asAdmin() {
		return as(admin());
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.with(asAdmin()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	/** A valid create form; {@code changes} overrides fields (null removes one). */
	MockHttpServletRequestBuilder form(String url, Map<String, String> changes) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("code", code());
		fields.put("description", "Flat deal on festival orders.");
		fields.put("type", "PERCENT");
		fields.put("value", "20");
		fields.put("maxDiscount", "120");
		fields.put("minOrder", "249");
		fields.put("validFrom", "2026-10-04T10:00");
		fields.put("validUntil", "2026-11-01T00:00");
		fields.put("totalLimit", "500");
		fields.put("perUserLimit", "2");
		fields.put("restaurantId", "");
		fields.put("active", "true");
		fields.put("_active", "on");
		fields.put("_firstOrderOnly", "on");
		fields.putAll(changes);
		MockHttpServletRequestBuilder request = post(url).with(csrf());
		fields.forEach((name, value) -> {
			if (value != null) {
				request.param(name, value);
			}
		});
		return request;
	}

	String rejected(String url, Map<String, String> changes) throws Exception {
		return mvc.perform(form(url, changes).with(asAdmin()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/coupon"))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	// ---------------------------------------------------------------- list

	@Test
	void theListShowsEachCouponsOfferPlaceWindowStateAndUsageNewestFirst() throws Exception {
		Restaurant kitchen = restaurant("Tambda Rassa House");
		clock.setTo(NOW.minusHours(5));
		Coupon live = coupon(CouponType.PERCENT, "50", c -> {
			c.setMaxDiscount(new BigDecimal("100"));
			c.setMinOrder(new BigDecimal("199"));
			c.setTotalLimit(5);
			c.setFirstOrderOnly(true);
		});
		clock.setTo(NOW.minusHours(4));
		Coupon scheduled = coupon(CouponType.FLAT, "75", c -> {
			c.setValidity(NOW.plusDays(2), null);
			c.setRestaurant(kitchen);
		});
		clock.setTo(NOW.minusHours(3));
		Coupon ended = coupon(CouponType.FLAT, "40", c -> c.setValidity(NOW.minusDays(10), NOW));
		clock.setTo(NOW.minusHours(2));
		Coupon off = coupon(CouponType.FLAT, "30", c -> c.setActive(false));
		clock.setTo(NOW.minusHours(1));
		Coupon claimed = coupon(CouponType.FLAT, "60", c -> c.setTotalLimit(2));
		clock.reset();
		used(live, 2, "60.50");
		used(claimed, 2, "60");

		String page = html(get("/admin/coupons"));
		assertThat(page).contains("<title>Coupons | Zaika</title>")
			.contains("<a class=\"tab\" href=\"/admin/coupons\" aria-current=\"page\"")
			.contains("href=\"/admin/coupons/new\"")
			.containsSubsequence(claimed.getCode(), off.getCode(), ended.getCode(), scheduled.getCode(), live.getCode());

		assertThat(row(page, live)).contains("50% off up to ₹100")
			.contains("Min. order ₹199 · First order only")
			.contains("Every restaurant").contains("From 3 Oct 2026").contains("No end date")
			.contains(">Live<").contains("2 of 5").contains("₹121 off in all")
			.contains("href=\"/admin/coupons/" + live.getId() + "\"");
		assertThat(row(page, scheduled)).contains("₹75 off").contains("Tambda Rassa House").contains(">Scheduled<")
			.contains("From 6 Oct 2026").doesNotContain("off in all");
		assertThat(row(page, ended)).contains(">Ended<").contains("Till 4 Oct 2026, 7:29 PM");
		assertThat(row(page, off)).contains(">Off<");
		assertThat(row(page, claimed)).contains(">Fully claimed<").contains("2 of 2").contains("₹120 off in all");
	}

	/** The table row of one coupon. */
	static String row(String page, Coupon c) {
		int start = page.indexOf("<tr> <th scope=\"row\"> <a class=\"admin-coupon-code\" href=\"/admin/coupons/" + c.getId() + "\"");
		assertThat(start).as("row of " + c.getCode()).isPositive();
		return page.substring(start, page.indexOf("</tr>", start));
	}

	@Test
	void theListPagesByTwentyWithCanonicalUrls() throws Exception {
		for (long n = coupons.count(); n < 21; n++) {
			coupon(CouponType.FLAT, "10", c -> {
			});
		}
		long total = coupons.count();
		int last = (int) ((total + 19) / 20);
		assertThat(html(get("/admin/coupons"))).contains("Showing 1–20 of " + total + " coupons, newest first")
			.contains("href=\"/admin/coupons?page=2\"");
		assertThat(html(get("/admin/coupons?page=2"))).contains("Showing 21–");

		for (String junk : List.of("?page=1", "?page=0", "?page=abc", "?page=-3", "?page=")) {
			mvc.perform(get(URI.create("/admin/coupons" + junk)).with(asAdmin()))
				.andExpect(redirectedUrl("/admin/coupons"));
		}
		mvc.perform(get("/admin/coupons?page=999").with(asAdmin()))
			.andExpect(redirectedUrl("/admin/coupons?page=" + last));
		mvc.perform(get("/admin/coupons?page=2&x=1").with(asAdmin())).andExpect(redirectedUrl("/admin/coupons?page=2"));
	}

	@Test
	void theListRunsAFixedNumberOfQueries() throws Exception {
		RequestPostProcessor signedIn = asAdmin();
		Coupon first = coupon(CouponType.FLAT, "10", c -> c.setRestaurant(restaurant("Query Kitchen")));
		used(first, 1, "10");
		mvc.perform(get("/admin/coupons").with(signedIn)).andExpect(status().isOk()); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		mvc.perform(get("/admin/coupons").with(signedIn)).andExpect(status().isOk());
		long before = stats.getPrepareStatementCount();
		for (int i = 0; i < 4; i++) {
			Coupon more = coupon(CouponType.FLAT, "10", c -> c.setRestaurant(restaurant("Query Kitchen")));
			used(more, 2, "10");
		}
		stats.clear();
		mvc.perform(get("/admin/coupons").with(signedIn)).andExpect(status().isOk());
		// coupons with their restaurants, the count, usage of the page: none per coupon
		assertThat(stats.getPrepareStatementCount()).isEqualTo(before);
	}

	// ---------------------------------------------------------------- create

	@Test
	void theNewCouponFormStartsWithSensibleDefaults() throws Exception {
		Restaurant kitchen = restaurant("Shahu Misal Corner");
		String page = html(get("/admin/coupons/new"));
		assertThat(page).contains("<title>New coupon | Zaika</title>")
			.contains("<h1 id=\"coupon-form-title\" class=\"owner-title\">New coupon</h1>")
			.contains("action=\"/admin/coupons/new\"")
			.contains("type=\"datetime-local\" id=\"validFrom\" name=\"validFrom\" value=\"2026-10-04T19:30\"")
			.contains("id=\"perUserLimit\" name=\"perUserLimit\" value=\"1\"")
			.contains("value=\"PERCENT\" id=\"type1\" name=\"type\" checked=\"checked\"")
			.contains("id=\"active1\" name=\"active\" value=\"true\" checked=\"checked\"")
			.contains("id=\"firstOrderOnly1\" name=\"firstOrderOnly\" value=\"true\">")
			.contains("<option value=\"\" selected=\"selected\">Every restaurant</option>")
			.contains("<option value=\"" + kitchen.getId() + "\">Shahu Misal Corner · Rajarampuri</option>")
			.doesNotContain("Delete coupon");
	}

	@Test
	void creatingACouponSavesEveryFieldAndCustomersSeeTheOffer() throws Exception {
		Restaurant kitchen = restaurant("Panhala Spice Kitchen");
		String typed = "  diwali " + code().substring(1, 8).toLowerCase() + " ";
		String code = typed.replace(" ", "").toUpperCase();
		mvc.perform(form("/admin/coupons/new", Map.of("code", typed, "type", "PERCENT", "value", "15.5",
				"validFrom", "2026-10-01T09:00", "restaurantId", String.valueOf(kitchen.getId()), "firstOrderOnly",
				"true")).with(asAdmin()))
			.andExpect(redirectedUrl("/admin/coupons"))
			.andExpect(flash().attribute("flashSuccess", code + " is saved. Customers can use it from its start time."));

		Coupon saved = coupons.findByCode(code).orElseThrow();
		assertThat(saved.getDescription()).isEqualTo("Flat deal on festival orders.");
		assertThat(saved.getType()).isEqualTo(CouponType.PERCENT);
		assertThat(saved.getValue()).isEqualByComparingTo("15.50");
		assertThat(saved.getMaxDiscount()).isEqualByComparingTo("120.00");
		assertThat(saved.getMinOrder()).isEqualByComparingTo("249.00");
		assertThat(saved.getValidFrom()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 0));
		assertThat(saved.getValidUntil()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
		assertThat(saved.getTotalLimit()).isEqualTo(500);
		assertThat(saved.getPerUserLimit()).isEqualTo(2);
		assertThat(saved.isFirstOrderOnly()).isTrue();
		assertThat(saved.isActive()).isTrue();
		assertThat(couponService.offers(kitchen.getId())).extracting(CouponOffer::code).contains(code);
		assertThat(couponService.offers(restaurant("Other Kitchen").getId())).extracting(CouponOffer::code)
			.doesNotContain(code);

		String list = html(get("/admin/coupons"));
		assertThat(row(list, saved)).contains("15.5% off up to ₹120").contains("Panhala Spice Kitchen")
			.contains(">Live<").contains("0 of 500");
	}

	@Test
	void aFlatCouponWithEmptyOptionalFieldsHasNoLimitsAndCanStartSwitchedOff() throws Exception {
		String code = code();
		Map<String, String> changes = new LinkedHashMap<>();
		changes.put("code", code);
		changes.put("type", "FLAT");
		changes.put("value", "100");
		changes.put("maxDiscount", "");
		changes.put("minOrder", "");
		changes.put("validUntil", "");
		changes.put("totalLimit", "");
		changes.put("perUserLimit", "");
		changes.put("active", null);
		mvc.perform(form("/admin/coupons/new", changes).with(asAdmin()))
			.andExpect(redirectedUrl("/admin/coupons"))
			.andExpect(flash().attribute("flashSuccess", code + " is saved. It stays off until you switch it on."));
		Coupon saved = coupons.findByCode(code).orElseThrow();
		assertThat(saved.getMaxDiscount()).isNull();
		assertThat(saved.getMinOrder()).isEqualByComparingTo("0.00");
		assertThat(saved.getValidUntil()).isNull();
		assertThat(saved.getTotalLimit()).isNull();
		assertThat(saved.getPerUserLimit()).isNull();
		assertThat(saved.isActive()).isFalse();
		assertThat(saved.isFirstOrderOnly()).isFalse();
	}

	@Test
	void everyRuleHasAnInlineMessageAndTheTypedValuesStay() throws Exception {
		long before = coupons.count();
		String blank = rejected("/admin/coupons/new", Map.of("code", " ", "description", "", "value", ""));
		assertThat(blank).contains("A few details need another look.")
			.contains("Give the coupon a code, like DIWALI100.")
			.contains("Write a line customers see under the code, like Flat ₹100 off on Diwali orders.")
			.contains("Enter the discount.")
			.contains("id=\"code\" name=\"code\" value=\"\" autocomplete=\"off\" aria-invalid=\"true\"")
			.contains("value=\"249\""); // what was typed elsewhere stays

		assertThat(rejected("/admin/coupons/new", Map.of("code", "AB-12")))
			.contains("Use 3 to 20 letters and digits, no spaces or symbols.");
		assertThat(rejected("/admin/coupons/new", Map.of("value", "101"))).contains("A percentage goes up to 100.");
		assertThat(rejected("/admin/coupons/new", Map.of("value", "0"))).contains("The discount must be more than zero.");
		assertThat(rejected("/admin/coupons/new", Map.of("value", "10.555"))).contains("Use an amount like 50 or 99.50.");
		assertThat(rejected("/admin/coupons/new", Map.of("value", "abc")))
			.contains("Enter an amount in rupees, like 250.");
		assertThat(rejected("/admin/coupons/new", Map.of("type", "FLAT", "value", "500")))
			.contains("A cap only applies to percentage coupons. Leave it empty.");
		assertThat(rejected("/admin/coupons/new", Map.of("type", "BOGUS")))
			.contains("Choose a percentage or a flat amount.");
		assertThat(rejected("/admin/coupons/new", Map.of("minOrder", "-1")))
			.contains("The minimum order cannot be negative.");
		assertThat(rejected("/admin/coupons/new", Map.of("validUntil", "2026-10-04T10:00")))
			.contains("The end must be after the start.");
		assertThat(rejected("/admin/coupons/new", Map.of("validFrom", "", "validUntil", "")))
			.contains("Pick when the coupon starts working.");
		assertThat(rejected("/admin/coupons/new", Map.of("validFrom", "tomorrow"))).contains("Pick a date and time.");
		assertThat(rejected("/admin/coupons/new", Map.of("totalLimit", "2", "perUserLimit", "3")))
			.contains("A customer cannot use it more often than the total limit.");
		assertThat(rejected("/admin/coupons/new", Map.of("totalLimit", "0")))
			.contains("Allow at least 1 use, or leave it empty for no limit.");
		assertThat(rejected("/admin/coupons/new", Map.of("totalLimit", "1.5"))).contains("Enter a whole number, like 100.");
		assertThat(rejected("/admin/coupons/new", Map.of("restaurantId", "999999")))
			.contains("Choose a restaurant from the list.");
		assertThat(rejected("/admin/coupons/new", Map.of("restaurantId", "x")))
			.contains("Choose a restaurant from the list.");
		assertThat(rejected("/admin/coupons/new", Map.of("description", "<b>Big</b> deal", "value", "101")))
			.contains("&lt;b&gt;Big&lt;/b&gt; deal").doesNotContain("<b>Big</b>");

		Coupon taken = coupon(CouponType.FLAT, "50", c -> {
		});
		assertThat(rejected("/admin/coupons/new", Map.of("code", taken.getCode().toLowerCase())))
			.contains(taken.getCode() + " is already taken. Pick another code.");
		assertThat(coupons.count()).isEqualTo(before + 1);
	}

	// ---------------------------------------------------------------- edit

	@Test
	void theEditPageIsPrefilledWithTheCodeReadOnlyAndShowsUsage() throws Exception {
		Restaurant kitchen = restaurant("Rankala Tiffin Room");
		Coupon c = coupon(CouponType.PERCENT, "50", x -> {
			x.setMaxDiscount(new BigDecimal("100"));
			x.setTotalLimit(10);
			x.setRestaurant(kitchen);
			x.setValidity(NOW.minusDays(1).withSecond(42), NOW.plusDays(30));
		});
		used(c, 3, "45.25");
		used(c, 1, "50");

		String page = html(get("/admin/coupons/" + c.getId()));
		assertThat(page).contains("<title>" + c.getCode() + " | Zaika</title>")
			.contains("action=\"/admin/coupons/" + c.getId() + "\"")
			.contains("id=\"code\" name=\"code\" type=\"text\" readonly value=\"" + c.getCode() + "\"")
			.contains("Codes can't change once created")
			.contains("id=\"value\" name=\"value\" value=\"50\"")
			.contains("id=\"maxDiscount\" name=\"maxDiscount\" value=\"100\"")
			.contains("id=\"minOrder\" name=\"minOrder\" value=\"0\"")
			.contains("name=\"validFrom\" value=\"2026-10-03T19:30\"")
			.contains("name=\"validUntil\" value=\"2026-11-03T19:30\"")
			.contains("<option value=\"" + kitchen.getId() + "\" selected=\"selected\">Rankala Tiffin Room · Rajarampuri</option>")
			.contains("4 of 10").contains("<dd>2</dd>").contains("₹185.75")
			.contains("Used on 4 orders, so it stays for the records.").doesNotContain("Delete coupon")
			.contains("50% off up to ₹100").contains(">Live<");
	}

	@Test
	void savingAnEditChangesEverythingButTheCode() throws Exception {
		Restaurant kitchen = restaurant("Mahadwar Snacks");
		Coupon c = coupon(CouponType.PERCENT, "20", x -> x.setRestaurant(kitchen));
		Map<String, String> changes = new LinkedHashMap<>();
		changes.put("code", "HACKED1");
		changes.put("description", "Now a flat deal.");
		changes.put("type", "FLAT");
		changes.put("value", "75");
		changes.put("maxDiscount", "");
		changes.put("totalLimit", "");
		changes.put("restaurantId", "");
		changes.put("active", null);
		mvc.perform(form("/admin/coupons/" + c.getId(), changes).with(asAdmin()))
			.andExpect(redirectedUrl("/admin/coupons/" + c.getId()))
			.andExpect(flash().attribute("flashSuccess", c.getCode() + " is updated."));

		Coupon saved = coupons.findWithRestaurantById(c.getId()).orElseThrow();
		assertThat(saved.getCode()).isEqualTo(c.getCode());
		assertThat(coupons.findByCode("HACKED1")).isEmpty();
		assertThat(saved.getDescription()).isEqualTo("Now a flat deal.");
		assertThat(saved.getType()).isEqualTo(CouponType.FLAT);
		assertThat(saved.getValue()).isEqualByComparingTo("75.00");
		assertThat(saved.getMaxDiscount()).isNull();
		assertThat(saved.getTotalLimit()).isNull();
		assertThat(saved.getRestaurant()).isNull();
		assertThat(saved.isActive()).isFalse();
		assertThat(couponService.offers(kitchen.getId())).extracting(CouponOffer::code).doesNotContain(c.getCode());
		assertThat(html(get("/admin/coupons/" + c.getId()))).contains(">Off<").contains("₹75 off");
	}

	@Test
	void anEditWithErrorsKeepsTheStoredCodeAndChangesNothing() throws Exception {
		Coupon c = coupon(CouponType.FLAT, "40", x -> {
		});
		String page = rejected("/admin/coupons/" + c.getId(), Map.of("code", "OTHER1", "type", "PERCENT", "value", "150"));
		assertThat(page).contains("A percentage goes up to 100.")
			.contains("readonly value=\"" + c.getCode() + "\"").doesNotContain("OTHER1")
			.contains("<title>" + c.getCode() + " | Zaika</title>");
		Coupon unchanged = coupons.findById(c.getId()).orElseThrow();
		assertThat(unchanged.getType()).isEqualTo(CouponType.FLAT);
		assertThat(unchanged.getValue()).isEqualByComparingTo("40.00");
	}

	// ---------------------------------------------------------------- delete

	@Test
	void anUnusedCouponIsDeletedAndCartsHoldingItLoseIt() throws Exception {
		Coupon c = coupon(CouponType.FLAT, "40", x -> {
		});
		User customer = orders.customer();
		jdbc.update("INSERT INTO carts (user_id, coupon_id, created_at, updated_at, version) VALUES (?, ?, ?, ?, 0)",
				customer.getId(), c.getId(), NOW, NOW);
		assertThat(html(get("/admin/coupons/" + c.getId()))).contains("Delete coupon")
			.contains("Delete " + c.getCode() + "? This can&#39;t be undone.")
			.contains("action=\"/admin/coupons/" + c.getId() + "/delete\"");

		mvc.perform(post("/admin/coupons/" + c.getId() + "/delete").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/coupons"))
			.andExpect(flash().attribute("flashSuccess", c.getCode() + " is deleted."));
		assertThat(coupons.findById(c.getId())).isEmpty();
		assertThat(jdbc.queryForObject("SELECT coupon_id FROM carts WHERE user_id = ?", Long.class, customer.getId()))
			.isNull();
	}

	@Test
	void aUsedCouponIsKeptForTheRecords() throws Exception {
		Coupon c = coupon(CouponType.FLAT, "40", x -> {
		});
		used(c, 1, "40");
		mvc.perform(post("/admin/coupons/" + c.getId() + "/delete").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/coupons/" + c.getId()))
			.andExpect(flash().attribute("flashError",
					c.getCode() + " was used on one order, so it stays for the records. Switch it off instead."));
		assertThat(coupons.findById(c.getId())).isPresent();
	}

	@Test
	void unknownCouponsAre404() throws Exception {
		mvc.perform(get("/admin/coupons/999999").with(asAdmin())).andExpect(status().isNotFound());
		mvc.perform(form("/admin/coupons/999999", Map.of()).with(asAdmin())).andExpect(status().isNotFound());
		mvc.perform(post("/admin/coupons/999999/delete").with(asAdmin()).with(csrf()))
			.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- security

	@Test
	void onlyAdminsManageCoupons() throws Exception {
		Coupon c = coupon(CouponType.FLAT, "40", x -> {
		});
		String edit = "/admin/coupons/" + c.getId();
		for (String url : List.of("/admin/coupons", "/admin/coupons/new", edit)) {
			mvc.perform(get(url)).andExpect(redirectedUrl("/login"));
		}
		mvc.perform(form("/admin/coupons/new", Map.of())).andExpect(redirectedUrl("/login"));
		mvc.perform(post(edit + "/delete").with(csrf())).andExpect(redirectedUrl("/login"));

		Restaurant kitchen = restaurant("Guarded Coupons Kitchen");
		User owner = users.findByEmailIgnoreCase(kitchen.getSlug() + "@fixtures.zaika.test").orElseThrow();
		for (User notAdmin : List.of(orders.customer(), owner, orders.rider())) {
			for (String url : List.of("/admin/coupons", "/admin/coupons/new", edit)) {
				mvc.perform(get(url).with(as(notAdmin))).andExpect(status().isForbidden());
			}
			mvc.perform(form("/admin/coupons/new", Map.of()).with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(form(edit, Map.of("value", "1")).with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(post(edit + "/delete").with(as(notAdmin)).with(csrf())).andExpect(status().isForbidden());
		}
		mvc.perform(post(edit + "/delete").with(asAdmin())).andExpect(status().isForbidden()); // no CSRF token
		assertThat(coupons.findById(c.getId())).get().extracting(Coupon::getValue)
			.satisfies(v -> assertThat(v).isEqualByComparingTo("40.00"));
	}

}
