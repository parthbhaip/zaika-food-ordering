package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;
import com.zaika.user.UserService;

/**
 * T40: accounts ({@code /admin/users}): list and search, one account with its orders, enable / disable (never your
 * own account, never the last active admin, roles untouched) and a disabled account being signed out of live
 * sessions. The list is platform-wide and the tests share one database, so names carry a unique word.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:adminusers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserAdminTest {

	static final String PASSWORD = "Masala2026";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	UserService userService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	MenuItemRepository items;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static String word() {
		return "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	/** An account that can sign in with {@link #PASSWORD}. */
	User account(String name, Role role) {
		return users.save(new User(name, "u-" + UUID.randomUUID() + "@users.zaika.test", "9822012345",
				passwordEncoder.encode(PASSWORD), role));
	}

	User admin() {
		return account("Aditi Kulkarni", Role.ADMIN);
	}

	User reload(User u) {
		return users.findById(u.getId()).orElseThrow();
	}

	RequestPostProcessor as(User user) {
		return user(userDetails.loadUserByUsername(user.getEmail()));
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	String list(User admin, String query) throws Exception {
		return html(get(URI.create("/admin/users" + query)).with(as(admin)));
	}

	MockHttpServletRequestBuilder change(User admin, User target, String action) {
		return post("/admin/users/" + target.getId() + "/" + action).with(as(admin)).with(csrf());
	}

	MockHttpServletRequestBuilder login(User account) {
		return post("/login").param("email", account.getEmail()).param("password", PASSWORD).with(csrf());
	}

	long dish(String name) {
		Restaurant r = fixtures.restaurant("users-" + UUID.randomUUID().toString().substring(0, 8), name)
			.dishes("Misal Pav")
			.save();
		return items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(r.getId()).get(0).getId();
	}

	// ---------------------------------------------------------------- list

	@Test
	void theListShowsEveryRoleNewestFirstWithChipsAndNoPhoneNumbers() throws Exception {
		String w = word();
		clock.setTo(LocalDateTime.of(2026, 10, 1, 10, 0));
		account(w + " Customer One", Role.CUSTOMER);
		clock.setTo(LocalDateTime.of(2026, 10, 3, 10, 0));
		User rider = account(w + " Rider Two", Role.DELIVERY_PARTNER);
		clock.setTo(LocalDateTime.of(2026, 10, 2, 10, 0));
		User off = account(w + " Owner Three", Role.RESTAURANT_OWNER);
		off.setEnabled(false);
		users.save(off);
		clock.reset();
		User admin = admin();

		String all = list(admin, "?q=" + w);
		assertThat(all).contains("<title>Users | Zaika</title>")
			.contains("Showing 1–3 of 3 accounts, newest first")
			.containsSubsequence(w + " Rider Two", w + " Owner Three", w + " Customer One")
			.contains("Delivery partner").contains("Restaurant owner").contains("Customer")
			.contains("Disabled").contains("Active")
			.contains("href=\"/admin/users/" + rider.getId() + "\"")
			.contains(rider.getEmail())
			.contains("href=\"/admin/users?role=delivery-partner&amp;q=" + w + "\"")
			.contains("<a class=\"tab\" href=\"/admin/users\" aria-current=\"page\"")
			.doesNotContain("9822012345").doesNotContain("98220 12345").doesNotContain("98••• ••345");

		String riders = list(admin, "?role=delivery-partner&q=" + w);
		assertThat(riders).contains(w + " Rider Two").doesNotContain(w + " Customer One").contains("One account")
			.contains("<input type=\"hidden\" name=\"role\" value=\"delivery-partner\">");

		long customers = users.findAll().stream().filter(u -> u.getRole() == Role.CUSTOMER).count();
		assertThat(all).contains("Customers <span class=\"admin-chip-count\">" + customers + "</span>");
	}

	@Test
	void searchMatchesNameAndEmailLiterallyAndEscapes() throws Exception {
		String w = word();
		User target = account(w + " <b>Bold</b>", Role.CUSTOMER);
		User admin = admin();
		assertThat(list(admin, "?q=" + URLEncoder.encode(target.getEmail(), StandardCharsets.UTF_8)))
			.contains(w + " &lt;b&gt;Bold&lt;/b&gt;").doesNotContain("<b>Bold</b>");
		assertThat(list(admin, "?q=" + w.toUpperCase())).contains("One account");
		assertThat(list(admin, "?q=" + w + "%25")).contains("No accounts match").contains("Check the spelling");
		assertThat(html(get("/admin/users/" + target.getId()).with(as(admin))))
			.contains(w + " &lt;b&gt;Bold&lt;/b&gt;").doesNotContain("<b>Bold</b>");
	}

	@Test
	void nonCanonicalListUrlsRedirectAndTheListPagesByTwenty() throws Exception {
		User admin = admin();
		for (String[] pair : List.of(new String[] { "?role=", "/admin/users" },
				new String[] { "?role=RESTAURANT_OWNER", "/admin/users" },
				new String[] { "?role=Restaurant-Owner", "/admin/users?role=restaurant-owner" },
				new String[] { "?page=1", "/admin/users" }, new String[] { "?page=-3", "/admin/users" },
				new String[] { "?q=%20", "/admin/users" })) {
			mvc.perform(get(URI.create("/admin/users" + pair[0])).with(as(admin))).andExpect(redirectedUrl(pair[1]));
		}
		String w = word();
		for (int i = 0; i < 21; i++) {
			account(w + " Person " + i, Role.CUSTOMER);
		}
		assertThat(list(admin, "?q=" + w)).contains("Showing 1–20 of 21 accounts, newest first")
			.contains("href=\"/admin/users?q=" + w + "&amp;page=2\"");
		assertThat(list(admin, "?q=" + w + "&page=2")).contains("Showing 21–21 of 21 accounts, newest first");
		mvc.perform(get(URI.create("/admin/users?q=" + w + "&page=9")).with(as(admin)))
			.andExpect(redirectedUrl("/admin/users?q=" + w + "&page=2"));
	}

	// ---------------------------------------------------------------- one account

	@Test
	void theAccountPageShowsDetailsAndTheOrdersPlacedNewestFirstTenAPage() throws Exception {
		User customer = account("Riya Shinde", Role.CUSTOMER);
		long dish = dish("Users Misal Corner");
		Order first = orders.place(customer.getId(), dish, 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		clock.advance(Duration.ofMinutes(5));
		Order second = orders.place(customer.getId(), dish, 2, PaymentMethod.COD, OrderStatus.PLACED);
		User admin = admin();

		String page = html(get("/admin/users/" + customer.getId()).with(as(admin)));
		assertThat(page).contains("<title>Account | Zaika</title>").contains("Riya Shinde").contains(customer.getEmail())
			.contains("+91 98••• ••345").doesNotContain("9822012345").doesNotContain("98220 12345")
			.doesNotContain(customer.getPasswordHash())
			.contains("2 orders").containsSubsequence(second.getCode(), first.getCode())
			.contains("Users Misal Corner").contains("Delivered")
			.contains("action=\"/admin/users/" + customer.getId() + "/disable\"")
			.doesNotContain("/enable\"");

		for (int i = 0; i < 9; i++) {
			orders.place(customer.getId(), dish, 1, PaymentMethod.COD, OrderStatus.PLACED);
		}
		assertThat(html(get("/admin/users/" + customer.getId()).with(as(admin)))).contains("11 orders")
			.contains("href=\"/admin/users/" + customer.getId() + "?page=2\"").doesNotContain(first.getCode());
		assertThat(html(get("/admin/users/" + customer.getId() + "?page=2").with(as(admin)))).contains(first.getCode());
		mvc.perform(get("/admin/users/" + customer.getId() + "?page=1").with(as(admin)))
			.andExpect(redirectedUrl("/admin/users/" + customer.getId()));
		mvc.perform(get("/admin/users/" + customer.getId() + "?page=7").with(as(admin)))
			.andExpect(redirectedUrl("/admin/users/" + customer.getId() + "?page=2"));
		mvc.perform(get("/admin/users/" + customer.getId() + "?page=x").with(as(admin)))
			.andExpect(redirectedUrl("/admin/users/" + customer.getId()));
	}

	@Test
	void ownersAndRidersLinkToTheirOtherAdminPagesAndLocksShow() throws Exception {
		User admin = admin();
		String slug = "users-own-" + UUID.randomUUID().toString().substring(0, 8);
		fixtures.restaurant(slug, "Linked Kitchen").save();
		User owner = users.findByEmailIgnoreCase(slug + "@fixtures.zaika.test").orElseThrow();
		assertThat(html(get("/admin/users/" + owner.getId()).with(as(admin))))
			.contains("href=\"/admin/restaurants/" + slug + "\"").contains("Only customer accounts place orders.");

		User rider = account("Sagar Patil", Role.DELIVERY_PARTNER);
		profiles.save(new DeliveryPartnerProfile(rider, VehicleType.BIKE, "MH09AB1234", "Rajarampuri"));
		assertThat(html(get("/admin/users/" + rider.getId()).with(as(admin))))
			.contains("href=\"/admin/riders/" + rider.getId() + "\"");

		User locked = account("Locked Out", Role.CUSTOMER);
		for (int i = 0; i < UserService.MAX_FAILED_LOGINS; i++) {
			userService.recordFailedLogin(locked.getEmail());
		}
		assertThat(html(get("/admin/users/" + locked.getId()).with(as(admin))))
			.contains("Locked after wrong passwords until 7:45 PM");

		mvc.perform(get("/admin/users/999999").with(as(admin))).andExpect(status().isNotFound());
		mvc.perform(post("/admin/users/999999/disable").with(as(admin)).with(csrf())).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- enable / disable

	@Test
	void disablingStopsSignInAndEnablingRestoresItWithoutTouchingTheRole() throws Exception {
		User admin = admin();
		User customer = account("Riya Shinde", Role.CUSTOMER);

		mvc.perform(change(admin, customer, "disable"))
			.andExpect(redirectedUrl("/admin/users/" + customer.getId()))
			.andExpect(flash().attribute("flashSuccess", "Riya Shinde's account is disabled and signed out everywhere."));
		assertThat(reload(customer).isEnabled()).isFalse();
		assertThat(reload(customer).getRole()).isEqualTo(Role.CUSTOMER);
		mvc.perform(login(customer)).andExpect(redirectedUrl("/login?disabled"));
		assertThat(html(get("/admin/users/" + customer.getId()).with(as(admin)))).contains("Disabled")
			.contains("action=\"/admin/users/" + customer.getId() + "/enable\"").doesNotContain("/disable\"");

		// asking again changes nothing
		mvc.perform(change(admin, customer, "disable")).andExpect(flash().attributeExists("flashSuccess"));

		mvc.perform(change(admin, customer, "enable").param("role", "ADMIN"))
			.andExpect(flash().attribute("flashSuccess", "Riya Shinde's account is active. They can sign in again."));
		assertThat(reload(customer).isEnabled()).isTrue();
		assertThat(reload(customer).getRole()).isEqualTo(Role.CUSTOMER);
		mvc.perform(login(customer)).andExpect(redirectedUrl("/"));
	}

	@Test
	void aDisabledAccountIsSignedOutOfItsLiveSessions() throws Exception {
		User admin = admin();
		User customer = account("Riya Shinde", Role.CUSTOMER);
		MockHttpSession session = (MockHttpSession) mvc.perform(login(customer))
			.andExpect(redirectedUrl("/"))
			.andReturn()
			.getRequest()
			.getSession(false);
		mvc.perform(get("/account").session(session)).andExpect(status().isOk());

		mvc.perform(change(admin, customer, "disable")).andExpect(status().isFound());
		mvc.perform(get("/account").session(session)).andExpect(redirectedUrl("/login?disabled"));
		mvc.perform(get("/account").session(session)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void aSessionStartedByRememberMeIsSignedOutAndTheCookieStopsWorking() throws Exception {
		User admin = admin();
		User customer = account("Riya Shinde", Role.CUSTOMER);
		jakarta.servlet.http.Cookie cookie = mvc.perform(login(customer).param("remember-me", "on"))
			.andExpect(redirectedUrl("/"))
			.andReturn()
			.getResponse()
			.getCookie("remember-me");
		assertThat(cookie).isNotNull();
		// a new browser session signed in by the cookie alone
		MockHttpSession session = (MockHttpSession) mvc.perform(get("/account").cookie(cookie))
			.andExpect(status().isOk())
			.andExpect(authenticated())
			.andReturn()
			.getRequest()
			.getSession(false);

		mvc.perform(change(admin, customer, "disable")).andExpect(status().isFound());
		mvc.perform(get("/account").session(session).cookie(cookie)).andExpect(redirectedUrl("/login?disabled"));
		mvc.perform(get("/account").cookie(cookie)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void aJustRegisteredAccountIsSignedOutWhenDisabledToo() throws Exception {
		User admin = admin();
		String email = "new-" + UUID.randomUUID() + "@users.zaika.test";
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/register").param("name", "Asha Patil")
				.param("email", email).param("phone", "9876543210").param("password", PASSWORD)
				.param("confirmPassword", PASSWORD).param("acceptTerms", "true").with(csrf()))
			.andExpect(status().isFound())
			.andExpect(authenticated())
			.andReturn()
			.getRequest()
			.getSession(false);
		mvc.perform(get("/account").session(session)).andExpect(status().isOk());

		User created = users.findByEmailIgnoreCase(email).orElseThrow();
		mvc.perform(change(admin, created, "disable")).andExpect(status().isFound());
		mvc.perform(get("/account").session(session)).andExpect(redirectedUrl("/login?disabled"));
	}

	@Test
	void disablingARiderTakesThemOffline() throws Exception {
		User admin = admin();
		User rider = account("Sagar Patil", Role.DELIVERY_PARTNER);
		DeliveryPartnerProfile p = new DeliveryPartnerProfile(rider, VehicleType.BIKE, "MH09AB1234", "Rajarampuri");
		p.changeStatus(RiderStatus.APPROVED, null);
		p.setOnline(true);
		profiles.save(p);
		assertThat(html(get("/admin/users/" + rider.getId()).with(as(admin)))).contains("They go offline now.");

		mvc.perform(change(admin, rider, "disable")).andExpect(status().isFound());
		DeliveryPartnerProfile after = profiles.findByUserId(rider.getId()).orElseThrow();
		assertThat(after.isOnline()).isFalse();
		assertThat(after.getStatus()).isEqualTo(RiderStatus.APPROVED);
	}

	@Test
	void anAdminCannotDisableTheirOwnAccount() throws Exception {
		User admin = admin();
		assertThat(html(get("/admin/users/" + admin.getId()).with(as(admin))))
			.contains("This is your own account, so you can&#39;t disable it.").doesNotContain("/disable\"");
		mvc.perform(change(admin, admin, "disable"))
			.andExpect(redirectedUrl("/admin/users/" + admin.getId()))
			.andExpect(flash().attribute("flashError", "This is your own account, so you can't disable it."));
		assertThat(reload(admin).isEnabled()).isTrue();
	}

	@Test
	void theLastActiveAdminCannotBeDisabledButAnotherAdminCan() throws Exception {
		User me = admin();
		User other = account("Second Admin", Role.ADMIN);
		mvc.perform(change(me, other, "disable")).andExpect(flash().attributeExists("flashSuccess"));
		assertThat(reload(other).isEnabled()).isFalse();
		assertThat(reload(other).getRole()).isEqualTo(Role.ADMIN);
		mvc.perform(change(me, other, "enable")).andExpect(flash().attributeExists("flashSuccess"));

		// every other admin in this database switched off: "other" is the only active one, and "me" (still signed
		// in from before being disabled) must not be able to switch it off
		for (User a : users.findAll()) {
			if (a.getRole() == Role.ADMIN && !a.getId().equals(other.getId())) {
				a.setEnabled(false);
				users.save(a);
			}
		}
		assertThat(html(get("/admin/users/" + other.getId()).with(as(me))))
			.contains("Second Admin is the only active admin, so the account can&#39;t be disabled.");
		mvc.perform(change(me, other, "disable"))
			.andExpect(flash().attribute("flashError",
					"Second Admin is the only active admin, so the account can't be disabled."));
		assertThat(reload(other).isEnabled()).isTrue();
	}

	@Test
	void thePageNeverOffersARoleChange() throws Exception {
		User admin = admin();
		User customer = account("Riya Shinde", Role.CUSTOMER);
		String page = html(get("/admin/users/" + customer.getId()).with(as(admin)));
		assertThat(page).doesNotContain("name=\"role\"").doesNotContain("Make admin").doesNotContain("Promote");
		mvc.perform(post("/admin/users/" + customer.getId() + "/role").param("role", "ADMIN").with(as(admin))
			.with(csrf())).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
		assertThat(reload(customer).getRole()).isEqualTo(Role.CUSTOMER);
	}

	// ---------------------------------------------------------------- security

	@Test
	void onlyAdminsManageAccounts() throws Exception {
		User target = account("Riya Shinde", Role.CUSTOMER);
		String detailUrl = "/admin/users/" + target.getId();
		mvc.perform(get("/admin/users")).andExpect(redirectedUrl("/login"));
		mvc.perform(get(detailUrl)).andExpect(redirectedUrl("/login"));
		mvc.perform(post(detailUrl + "/disable").with(csrf())).andExpect(redirectedUrl("/login"));

		for (Role role : List.of(Role.CUSTOMER, Role.RESTAURANT_OWNER, Role.DELIVERY_PARTNER)) {
			User notAdmin = account("Not Admin", role);
			mvc.perform(get("/admin/users").with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(get(detailUrl).with(as(notAdmin))).andExpect(status().isForbidden());
			for (String action : List.of("disable", "enable")) {
				mvc.perform(post(detailUrl + "/" + action).with(as(notAdmin)).with(csrf()))
					.andExpect(status().isForbidden());
			}
		}
		// no CSRF token
		mvc.perform(post(detailUrl + "/disable").with(as(admin()))).andExpect(status().isForbidden());
		assertThat(reload(target).isEnabled()).isTrue();
	}

}
