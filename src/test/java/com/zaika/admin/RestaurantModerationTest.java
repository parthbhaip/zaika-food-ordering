package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.notification.MailService;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T39: restaurant moderation ({@code /admin/restaurants}). The list is platform-wide and the tests share one database,
 * so each test gives its restaurants a unique word in the name and searches for it. Mails go through the
 * log-only {@link MailService}, spied to read what the owner gets.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:adminrestaurants;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true", "app.base-url=https://zaika.example.in" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RestaurantModerationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	MutableClock clock;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@MockitoSpyBean
	MailService mail;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static String word() {
		return "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	Restaurant restaurant(String name, RestaurantStatus status) {
		return fixtures.restaurant("mod-" + UUID.randomUUID().toString().substring(0, 8), name).status(status)
			.cuisines("Kolhapuri").dishes("Tambda Rassa", "Pandhra Rassa").save();
	}

	Restaurant reload(Restaurant r) {
		return restaurants.findBySlug(r.getSlug()).orElseThrow();
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
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	String list(String query) throws Exception {
		return html(get(URI.create("/admin/restaurants" + query)).with(asAdmin()));
	}

	String detail(Restaurant r) throws Exception {
		return html(get("/admin/restaurants/" + r.getSlug()).with(asAdmin()));
	}

	MockHttpServletRequestBuilder decide(Restaurant r, String action) {
		return post("/admin/restaurants/" + r.getSlug() + "/" + action).with(asAdmin()).with(csrf());
	}

	String mailTo(String email) {
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(mail).send(eq(email), anyString(), text.capture());
		return text.getValue();
	}

	String subjectTo(String email) {
		ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
		verify(mail).send(eq(email), subject.capture(), anyString());
		return subject.getValue();
	}

	// ---------------------------------------------------------------- list

	@Test
	void theListShowsEveryStatusNewestFirstWithCountsAndChips() throws Exception {
		String w = word();
		clock.setTo(LocalDateTime.of(2026, 10, 1, 10, 0));
		restaurant(w + " Old Mess", RestaurantStatus.APPROVED);
		clock.setTo(LocalDateTime.of(2026, 10, 3, 10, 0));
		restaurant(w + " Pending Cafe", RestaurantStatus.PENDING);
		clock.setTo(LocalDateTime.of(2026, 10, 2, 10, 0));
		restaurant(w + " Closed Dhaba", RestaurantStatus.SUSPENDED);
		clock.reset();

		String all = list("?q=" + w);
		assertThat(all).contains("<title>Restaurants | Zaika</title>")
			.contains("Showing 1–3 of 3 restaurants, newest first")
			.containsSubsequence(w + " Pending Cafe", w + " Closed Dhaba", w + " Old Mess")
			.contains("Pending review").contains("Live").contains("Suspended")
			.contains("href=\"/admin/restaurants/mod-")
			.contains("Owner of " + w + " Pending Cafe")
			.contains("3 Oct 2026")
			// chips keep the search
			.contains("href=\"/admin/restaurants?status=pending&amp;q=" + w + "\"")
			.contains("aria-current=\"page\"");

		String pending = list("?status=pending&q=" + w);
		assertThat(pending).contains(w + " Pending Cafe").doesNotContain(w + " Old Mess")
			.doesNotContain(w + " Closed Dhaba").contains("One restaurant");

		// the chip counts are over every restaurant, whatever the search
		long pendingCount = restaurants.findAll().stream().filter(r -> r.getStatus() == RestaurantStatus.PENDING).count();
		assertThat(list("?q=" + w)).contains("Pending review <span class=\"admin-chip-count is-waiting\">" + pendingCount
				+ "</span>");
	}

	@Test
	void searchMatchesNameAreaOwnerAndEmailWithWildcardsTakenLiterally() throws Exception {
		String w = word();
		Restaurant r = restaurant(w + " Tambda Corner", RestaurantStatus.APPROVED);
		restaurant("Other " + word(), RestaurantStatus.APPROVED);

		assertThat(list("?q=" + w.toUpperCase())).contains(w + " Tambda Corner");
		assertThat(list("?q=" + r.getSlug() + "%40fixtures.zaika.test")).contains(w + " Tambda Corner")
			.contains("One restaurant");
		assertThat(list("?q=Owner%20of%20" + w)).contains(w + " Tambda Corner");
		// % and _ are literal characters, not wildcards
		assertThat(list("?q=" + w + "%25")).contains("No restaurants match “" + w + "%”").contains("Clear search")
			.contains("href=\"/admin/restaurants\"");
	}

	@Test
	void namesAreEscapedOnTheListAndTheReviewPage() throws Exception {
		String w = word();
		Restaurant r = restaurant(w + " <b>Bold</b> Bites", RestaurantStatus.PENDING);
		assertThat(list("?q=" + w)).contains(w + " &lt;b&gt;Bold&lt;/b&gt; Bites").doesNotContain("<b>Bold</b>");
		assertThat(detail(r)).contains(w + " &lt;b&gt;Bold&lt;/b&gt; Bites").doesNotContain("<b>Bold</b>");
	}

	@Test
	void emptyStatesSayWhatToDo() throws Exception {
		assertThat(list("?q=" + word())).contains("No restaurants match").contains("search by the owner’s email");
	}

	@Test
	void nonCanonicalListUrlsRedirect() throws Exception {
		String w = word();
		String[][] cases = { { "?status=PENDING", "/admin/restaurants?status=pending" },
				{ "?status=nonsense", "/admin/restaurants" }, { "?status=", "/admin/restaurants" },
				{ "?q=", "/admin/restaurants" }, { "?q=%20%20", "/admin/restaurants" },
				{ "?page=1", "/admin/restaurants" }, { "?page=0", "/admin/restaurants" },
				{ "?page=abc", "/admin/restaurants" }, { "?q=%20" + w + "%20", "/admin/restaurants?q=" + w },
				{ "?q=" + w + "&status=approved", "/admin/restaurants?status=approved&q=" + w },
				{ "?q=" + w + "&page=7", "/admin/restaurants?q=" + w }, { "?foo=bar", "/admin/restaurants" } };
		User admin = admin();
		for (String[] c : cases) {
			mvc.perform(get(URI.create("/admin/restaurants" + c[0])).with(as(admin)))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl(c[1]));
		}
		mvc.perform(get("/admin/restaurants").with(as(admin)))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/restaurants"));
	}

	@Test
	void theListPagesByTwentyAndRunsAFixedNumberOfQueries() throws Exception {
		String w = word();
		for (int i = 0; i < 21; i++) {
			restaurant(w + " Kitchen " + i, RestaurantStatus.APPROVED);
		}
		User admin = admin();
		RequestPostProcessor signedIn = as(admin);
		String first = html(get(URI.create("/admin/restaurants?q=" + w)).with(signedIn));
		assertThat(first).contains("Showing 1–20 of 21 restaurants").contains("href=\"/admin/restaurants?q=" + w
				+ "&amp;page=2\"");
		String second = html(get(URI.create("/admin/restaurants?q=" + w + "&page=2")).with(signedIn));
		assertThat(second).contains("Showing 21–21 of 21 restaurants").contains(w + " Kitchen 0<");
		mvc.perform(get(URI.create("/admin/restaurants?q=" + w + "&page=3")).with(signedIn))
			.andExpect(redirectedUrl("/admin/restaurants?q=" + w + "&page=2"));

		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		mvc.perform(get(URI.create("/admin/restaurants?q=" + w)).with(signedIn)).andExpect(status().isOk());
		// page, count, counts per status (+ the signed-in user's cart count lookups): none per restaurant
		assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
	}

	// ---------------------------------------------------------------- review page

	@Test
	void theReviewPageShowsTheListingOwnerAndNumbersWithoutFullPhoneNumbers() throws Exception {
		Restaurant r = restaurant(word() + " Rassa House", RestaurantStatus.APPROVED);
		var menu = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		MenuItem dish = items.save(new MenuItem(menu, "Mutton Thali", new BigDecimal("250"), false));
		orders.place(orders.customer().getId(), dish.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		orders.placeThenCancel(orders.customer().getId(), dish.getId(), 1, PaymentMethod.COD);

		String html = detail(r);
		assertThat(html).contains("<title>Restaurant review | Zaika</title>")
			.contains("Restaurant review")
			.contains("Kolhapuri")
			.contains("11:00 AM to 11:00 PM")
			.contains("12, Main Road, Rajarampuri, Kolhapur 416008")
			.contains("/r/" + r.getSlug())
			.contains("Owner of " + r.getName())
			.contains(r.getSlug() + "@fixtures.zaika.test")
			.contains("+91 98••• ••210")
			.doesNotContain("9876543210").doesNotContain("98765 43210")
			.contains("Dishes on the menu</dt> <dd>3</dd>")
			.contains("1 placed")
			.contains("View public page")
			.contains("Suspend")
			.doesNotContain("Approve and go live")
			.contains("href=\"/admin/restaurants\"");
	}

	@Test
	void unknownRestaurantsAre404() throws Exception {
		User admin = admin();
		mvc.perform(get("/admin/restaurants/no-such-kitchen").with(as(admin))).andExpect(status().isNotFound());
		mvc.perform(post("/admin/restaurants/no-such-kitchen/approve").with(as(admin)).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/admin/restaurants/no-such-kitchen/suspend").param("reason", "x").with(as(admin))
			.with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/admin/restaurants/no-such-kitchen/reject").param("reason", "").with(as(admin))
			.with(csrf())).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- decisions

	@Test
	void approvingAPendingRestaurantPutsItLiveAndEmailsTheOwner() throws Exception {
		Restaurant r = restaurant(word() + " New Kitchen", RestaurantStatus.PENDING);
		assertThat(detail(r)).contains("Approve and go live").contains("Reject").doesNotContain("View public page");
		mvc.perform(get("/r/" + r.getSlug())).andExpect(status().isNotFound());

		mvc.perform(decide(r, "approve"))
			.andExpect(redirectedUrl("/admin/restaurants/" + r.getSlug()))
			.andExpect(flash().attribute("flashSuccess", r.getName() + " is approved and live. We've emailed the owner."));

		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.APPROVED);
		mvc.perform(get("/r/" + r.getSlug())).andExpect(status().isOk());
		String email = r.getSlug() + "@fixtures.zaika.test";
		assertThat(subjectTo(email)).isEqualTo(r.getName() + " is live on Zaika");
		assertThat(mailTo(email)).startsWith("Hi Owner,")
			.contains("https://zaika.example.in/r/" + r.getSlug())
			.contains("https://zaika.example.in/owner")
			.endsWith("Team Zaika\n");
	}

	@Test
	void rejectingNeedsAReasonThatTheOwnerSeesInTheMailAndOnTheirPanel() throws Exception {
		Restaurant r = restaurant(word() + " Doubtful Diner", RestaurantStatus.PENDING);

		String missing = html(decide(r, "reject").param("reason", "   "));
		assertThat(missing).contains("Write a reason. They see it in the email and on their panel.")
			.contains("aria-invalid=\"true\"")
			.contains("<details class=\"confirm admin-reason\" data-menu open");
		String tooLong = html(decide(r, "reject").param("reason", "<i>x</i>" + "y".repeat(500)));
		assertThat(tooLong).contains("Keep the reason under 500 characters.")
			.contains("&lt;i&gt;x&lt;/i&gt;yyy")
			.doesNotContain("<i>x</i>");
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.PENDING);
		verify(mail, never()).send(anyString(), anyString(), anyString());

		mvc.perform(decide(r, "reject").param("reason", "  The FSSAI licence number does not match.  "))
			.andExpect(redirectedUrl("/admin/restaurants/" + r.getSlug()))
			.andExpect(flash().attribute("flashSuccess", r.getName() + " is rejected. We've emailed the owner the reason."));

		Restaurant rejected = reload(r);
		assertThat(rejected.getStatus()).isEqualTo(RestaurantStatus.REJECTED);
		assertThat(rejected.getStatusReason()).isEqualTo("The FSSAI licence number does not match.");
		String email = r.getSlug() + "@fixtures.zaika.test";
		assertThat(subjectTo(email)).isEqualTo("We couldn't approve " + r.getName() + " yet");
		assertThat(mailTo(email)).contains("Reason: The FSSAI licence number does not match.")
			.contains("https://zaika.example.in/owner/restaurant");

		assertThat(detail(r)).contains("Reason given:").contains("The FSSAI licence number does not match.")
			.contains("Approve and go live").doesNotContain("Reject restaurant");
		User owner = users.findByEmailIgnoreCase(email).orElseThrow();
		assertThat(html(get("/owner").with(as(owner)))).contains("Reason: The FSSAI licence number does not match.");

		// a rejected restaurant can be approved once fixed
		mvc.perform(decide(r, "approve")).andExpect(flash().attribute("flashSuccess",
				r.getName() + " is approved and live. We've emailed the owner."));
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.APPROVED);
		assertThat(reload(r).getStatusReason()).isNull();
	}

	@Test
	void suspendingHidesARestaurantAndReactivatingBringsItBack() throws Exception {
		Restaurant r = restaurant(word() + " Late Kitchen", RestaurantStatus.APPROVED);
		String email = r.getSlug() + "@fixtures.zaika.test";

		assertThat(html(decide(r, "suspend").param("reason", ""))).contains("Write a reason.")
			.contains("Why suspend it?");

		mvc.perform(decide(r, "suspend").param("reason", "Repeated late deliveries this week."))
			.andExpect(redirectedUrl("/admin/restaurants/" + r.getSlug()))
			.andExpect(flash().attribute("flashSuccess",
					r.getName() + " is suspended and hidden from customers. We've emailed the owner."));
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.SUSPENDED);
		mvc.perform(get("/r/" + r.getSlug())).andExpect(status().isNotFound());
		assertThat(subjectTo(email)).isEqualTo(r.getName() + " is suspended on Zaika");
		assertThat(mailTo(email)).contains("Reason: Repeated late deliveries this week.");
		assertThat(detail(r)).contains("Reactivate").doesNotContain("Suspend restaurant")
			.contains("Repeated late deliveries this week.");
		User owner = users.findByEmailIgnoreCase(email).orElseThrow();
		assertThat(html(get("/owner").with(as(owner)))).contains("is suspended.")
			.contains("Reason: Repeated late deliveries this week.");

		clearInvocations(mail);
		mvc.perform(decide(r, "reactivate"))
			.andExpect(flash().attribute("flashSuccess", r.getName() + " is live again. We've emailed the owner."));
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.APPROVED);
		assertThat(reload(r).getStatusReason()).isNull();
		mvc.perform(get("/r/" + r.getSlug())).andExpect(status().isOk());
		assertThat(subjectTo(email)).isEqualTo(r.getName() + " is back on Zaika");
	}

	@Test
	void decisionsThatDoNotFitTheStatusAreRefusedWithoutMail() throws Exception {
		Restaurant live = restaurant(word() + " Live Kitchen", RestaurantStatus.APPROVED);
		Restaurant pending = restaurant(word() + " Waiting Kitchen", RestaurantStatus.PENDING);

		mvc.perform(decide(live, "approve")).andExpect(redirectedUrl("/admin/restaurants/" + live.getSlug()))
			.andExpect(flash().attribute("flashError", live.getName() + " is already live, so there is nothing to approve."));
		mvc.perform(decide(live, "reject").param("reason", "No"))
			.andExpect(flash().attribute("flashError", live.getName() + " is already live, so there is nothing to reject."));
		// a stale reject form without a reason gets the refusal, not the wrong form
		mvc.perform(decide(live, "reject").param("reason", ""))
			.andExpect(flash().attribute("flashError", live.getName() + " is already live, so there is nothing to reject."));
		mvc.perform(decide(pending, "suspend").param("reason", "Too slow"))
			.andExpect(flash().attribute("flashError", pending.getName() + " is still waiting for review. Approve or reject it first."));
		mvc.perform(decide(pending, "reactivate"))
			.andExpect(flash().attribute("flashError", pending.getName() + " is still waiting for review. Approve or reject it first."));

		assertThat(reload(live).getStatus()).isEqualTo(RestaurantStatus.APPROVED);
		assertThat(reload(pending).getStatus()).isEqualTo(RestaurantStatus.PENDING);
		verify(mail, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void aMailFailureNeverUndoesTheDecision() throws Exception {
		Restaurant r = restaurant(word() + " Offline Mail", RestaurantStatus.PENDING);
		doThrow(new MailSendException("SMTP down")).when(mail).send(anyString(), anyString(), anyString());

		mvc.perform(decide(r, "approve")).andExpect(status().isFound())
			.andExpect(flash().attribute("flashSuccess", r.getName() + " is approved and live. We've emailed the owner."));
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.APPROVED);
	}

	// ---------------------------------------------------------------- navigation and security

	@Test
	void theAdminNavAndTheDashboardLeadToPendingRestaurants() throws Exception {
		User admin = admin();
		String dashboard = html(get("/admin").with(as(admin)));
		assertThat(dashboard).contains("aria-label=\"Admin panel\"")
			.contains("href=\"/admin/restaurants?status=pending\"")
			.contains("Review restaurants");
		assertThat(html(get("/admin/restaurants").with(as(admin)))).contains("aria-label=\"Admin panel\"")
			.contains("<a class=\"tab\" href=\"/admin/restaurants\" aria-current=\"page\"");
	}

	@Test
	void onlyAdminsModerate() throws Exception {
		Restaurant r = restaurant(word() + " Guarded Kitchen", RestaurantStatus.PENDING);
		String detailUrl = "/admin/restaurants/" + r.getSlug();
		mvc.perform(get("/admin/restaurants")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(get(detailUrl)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(post(detailUrl + "/approve").with(csrf())).andExpect(redirectedUrl("/login"));

		User owner = users.findByEmailIgnoreCase(r.getSlug() + "@fixtures.zaika.test").orElseThrow();
		User rider = orders.rider();
		for (User notAdmin : List.of(orders.customer(), owner, rider)) {
			mvc.perform(get("/admin/restaurants").with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(get(detailUrl).with(as(notAdmin))).andExpect(status().isForbidden());
			for (String action : List.of("approve", "reject", "suspend", "reactivate")) {
				mvc.perform(post(detailUrl + "/" + action).param("reason", "Mine now").with(as(notAdmin)).with(csrf()))
					.andExpect(status().isForbidden());
			}
		}
		// no CSRF token
		mvc.perform(post(detailUrl + "/approve").with(asAdmin())).andExpect(status().isForbidden());
		assertThat(reload(r).getStatus()).isEqualTo(RestaurantStatus.PENDING);
		verify(mail, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void freeDeliveryAndNoMinimumReadNaturally() throws Exception {
		Restaurant r = restaurant(word() + " Free Ride", RestaurantStatus.PENDING);
		Restaurant stored = reload(r);
		stored.setDeliveryFee(BigDecimal.ZERO);
		restaurants.save(stored);
		assertThat(detail(r)).contains("Free delivery").contains("No minimum");
	}

}
