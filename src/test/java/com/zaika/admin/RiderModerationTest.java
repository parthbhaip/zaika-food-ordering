package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.RiderStatus;
import com.zaika.delivery.VehicleType;
import com.zaika.notification.MailService;
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

/**
 * T40: rider moderation ({@code /admin/riders}). The list is platform-wide and the tests share one database, so each
 * test gives its riders a unique word in the name and searches for it. Mails go through the log-only
 * {@link MailService}, spied to read what the rider gets.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:adminriders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"app.base-url=https://zaika.example.in" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RiderModerationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	DeliveryService deliveries;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	MutableClock clock;

	@MockitoSpyBean
	MailService mail;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static String word() {
		return "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	User rider(String name, RiderStatus status) {
		return rider(name, status, VehicleType.SCOOTER, "MH09AB1234", "Rajarampuri");
	}

	User rider(String name, RiderStatus status, VehicleType vehicle, String plate, String area) {
		User rider = users.save(new User(name, "r-" + UUID.randomUUID() + "@riders.zaika.test", "9822012345", "hash",
				Role.DELIVERY_PARTNER));
		DeliveryPartnerProfile p = new DeliveryPartnerProfile(rider, vehicle, plate, area);
		if (status != RiderStatus.PENDING) {
			p.changeStatus(status, status.needsReason() ? "Late deliveries" : null);
		}
		profiles.save(p);
		return rider;
	}

	DeliveryPartnerProfile profile(User rider) {
		return profiles.findByUserId(rider.getId()).orElseThrow();
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
		return html(get(URI.create("/admin/riders" + query)).with(asAdmin()));
	}

	String detail(User rider) throws Exception {
		return html(get("/admin/riders/" + rider.getId()).with(asAdmin()));
	}

	MockHttpServletRequestBuilder decide(User rider, String action) {
		return post("/admin/riders/" + rider.getId() + "/" + action).with(asAdmin()).with(csrf());
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
	void theListShowsEveryStatusNewestFirstWithChipsAndCounts() throws Exception {
		String w = word();
		clock.setTo(LocalDateTime.of(2026, 10, 1, 10, 0));
		rider(w + " Old Hand", RiderStatus.APPROVED);
		clock.setTo(LocalDateTime.of(2026, 10, 3, 10, 0));
		rider(w + " New Face", RiderStatus.PENDING, VehicleType.BICYCLE, null, "Shahupuri");
		clock.setTo(LocalDateTime.of(2026, 10, 2, 10, 0));
		rider(w + " Benched", RiderStatus.SUSPENDED);
		clock.reset();

		String all = list("?q=" + w);
		assertThat(all).contains("<title>Riders | Zaika</title>")
			.contains("Showing 1–3 of 3 riders, newest first")
			.containsSubsequence(w + " New Face", w + " Benched", w + " Old Hand")
			.contains("Under review").contains("Approved").contains("Suspended")
			.contains("Scooter · MH09AB1234").contains("Bicycle").contains("Rides around Shahupuri")
			.contains("3 Oct 2026")
			.contains("href=\"/admin/riders?status=pending&amp;q=" + w + "\"")
			.contains("<a class=\"tab\" href=\"/admin/riders\" aria-current=\"page\"")
			.doesNotContain("9822012345").doesNotContain("98220 12345");

		String pending = list("?status=pending&q=" + w);
		assertThat(pending).contains(w + " New Face").doesNotContain(w + " Old Hand").doesNotContain(w + " Benched")
			.contains("One rider").contains("<input type=\"hidden\" name=\"status\" value=\"pending\">");

		long waiting = profiles.countByStatus(RiderStatus.PENDING);
		assertThat(all).contains("Under review <span class=\"admin-chip-count is-waiting\">" + waiting + "</span>");
	}

	@Test
	void searchMatchesNameEmailAreaAndNumberPlate() throws Exception {
		String w = word();
		User byName = rider(w + " Name Match", RiderStatus.APPROVED);
		String plateWord = "MH09" + w.substring(1, 4).replaceAll("[0-9]", "Z").toUpperCase() + "4321";
		rider("Plate Rider " + w.substring(0, 3), RiderStatus.APPROVED, VehicleType.BIKE, plateWord, "Kasaba Bawada");

		assertThat(list("?q=" + w)).contains(w + " Name Match").contains("One rider");
		assertThat(list("?q=" + URLEncoder.encode(byName.getEmail(), StandardCharsets.UTF_8))).contains(w + " Name Match");
		assertThat(list("?q=" + plateWord.toLowerCase())).contains("Plate Rider");
		assertThat(list("?q=kasaba%20bawada")).contains("Plate Rider");
		// LIKE wildcards are searched literally
		assertThat(list("?q=" + w + "%25")).contains("No riders match").contains("Check the spelling");
	}

	@Test
	void namesAreEscapedAndEmptyStatesSayWhatToDo() throws Exception {
		String w = word();
		User r = rider(w + " <b>Bold</b>", RiderStatus.PENDING);
		assertThat(list("?q=" + w)).contains(w + " &lt;b&gt;Bold&lt;/b&gt;").doesNotContain("<b>Bold</b>");
		assertThat(detail(r)).contains(w + " &lt;b&gt;Bold&lt;/b&gt;").doesNotContain("<b>Bold</b>");
		assertThat(list("?q=" + word())).contains("No riders match");
	}

	@Test
	void nonCanonicalListUrlsRedirect() throws Exception {
		User admin = admin();
		for (String[] pair : List.of(new String[] { "?status=", "/admin/riders" },
				new String[] { "?status=PENDING", "/admin/riders?status=pending" },
				new String[] { "?status=nope", "/admin/riders" }, new String[] { "?page=1", "/admin/riders" },
				new String[] { "?page=abc", "/admin/riders" }, new String[] { "?q=%20%20", "/admin/riders" },
				new String[] { "?q=" + word() + "&page=4", null })) {
			var result = mvc.perform(get(URI.create("/admin/riders" + pair[0])).with(as(admin)))
				.andExpect(status().isFound())
				.andReturn();
			if (pair[1] != null) {
				assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(pair[1]);
			}
			else {
				assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("page=");
			}
		}
	}

	@Test
	void theListPagesByTwenty() throws Exception {
		String w = word();
		for (int i = 0; i < 21; i++) {
			rider(w + " Rider " + i, RiderStatus.PENDING);
		}
		assertThat(list("?q=" + w)).contains("Showing 1–20 of 21 riders, newest first")
			.contains("href=\"/admin/riders?q=" + w + "&amp;page=2\"");
		assertThat(list("?q=" + w + "&page=2")).contains("Showing 21–21 of 21 riders, newest first");
	}

	// ---------------------------------------------------------------- review page

	@Test
	void theReviewPageShowsVehicleContactAndDeliveriesWithoutTheFullPhone() throws Exception {
		User r = rider(word() + " Sagar", RiderStatus.APPROVED);
		DeliveryPartnerProfile p = profile(r);
		p.setOnline(true);
		profiles.save(p);
		Restaurant kitchen = fixtures.restaurant("rider-mod-" + UUID.randomUUID().toString().substring(0, 8),
				"Rider Mod Kitchen").dishes("Tambda Rassa").save();
		long dish = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(kitchen.getId()).get(0).getId();
		Order order = orders.place(orders.customer().getId(), dish, 1, PaymentMethod.COD, OrderStatus.READY_FOR_PICKUP);
		deliveries.claim(r.getId(), order.getCode());

		String page = detail(r);
		assertThat(page).contains("<title>Rider review | Zaika</title>")
			.contains("Scooter").contains("MH09AB1234").contains("Rajarampuri").contains(r.getEmail())
			.contains("+91 98••• ••345").doesNotContain("9822012345").doesNotContain("98220 12345")
			.contains("Online now")
			.contains("Delivering " + order.getCode() + " from Rider Mod Kitchen")
			.contains("Deliveries completed").contains("href=\"/admin/users/" + r.getId() + "\"")
			.contains("action=\"/admin/riders/" + r.getId() + "/suspend\"")
			.doesNotContain("/approve\"");
	}

	@Test
	void unknownRidersAre404() throws Exception {
		User admin = admin();
		mvc.perform(get("/admin/riders/999999").with(as(admin))).andExpect(status().isNotFound());
		// a customer account has no rider profile
		mvc.perform(get("/admin/riders/" + orders.customer().getId()).with(as(admin))).andExpect(status().isNotFound());
		mvc.perform(post("/admin/riders/999999/approve").with(as(admin)).with(csrf())).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- decisions

	@Test
	void approvingAPendingRiderLetsThemGoOnlineAndEmailsThem() throws Exception {
		User r = rider(word() + " Fresh", RiderStatus.PENDING);
		mvc.perform(decide(r, "approve"))
			.andExpect(redirectedUrl("/admin/riders/" + r.getId()))
			.andExpect(flash().attribute("flashSuccess", r.getName() + " is approved and can go online. We've emailed them."));
		assertThat(profile(r).getStatus()).isEqualTo(RiderStatus.APPROVED);
		assertThat(subjectTo(r.getEmail())).isEqualTo("You're approved to ride with Zaika");
		assertThat(mailTo(r.getEmail())).startsWith("Hi " + r.getName().split(" ")[0] + ",")
			.contains("https://zaika.example.in/partner");

		deliveries.setOnline(r.getId(), true);
		assertThat(profile(r).isOnline()).isTrue();
	}

	@Test
	void suspendingNeedsAReasonTakesTheRiderOfflineAndApprovingBringsThemBack() throws Exception {
		User r = rider(word() + " Speedy", RiderStatus.APPROVED);
		DeliveryPartnerProfile p = profile(r);
		p.setOnline(true);
		profiles.save(p);

		String missing = html(decide(r, "suspend").param("reason", "  "));
		assertThat(missing).contains("Write a reason. They see it in the email and on their panel.")
			.contains("aria-invalid=\"true\"").contains("<details class=\"confirm admin-reason\" data-menu open");
		String tooLong = html(decide(r, "suspend").param("reason", "<i>x</i>" + "y".repeat(500)));
		assertThat(tooLong).contains("Keep the reason under 500 characters.").contains("&lt;i&gt;x&lt;/i&gt;yyy");
		assertThat(profile(r).getStatus()).isEqualTo(RiderStatus.APPROVED);
		verify(mail, never()).send(anyString(), anyString(), anyString());

		mvc.perform(decide(r, "suspend").param("reason", "Three late deliveries this week."))
			.andExpect(redirectedUrl("/admin/riders/" + r.getId()))
			.andExpect(flash().attribute("flashSuccess", r.getName() + " is suspended and offline. We've emailed them the reason."));
		DeliveryPartnerProfile suspended = profile(r);
		assertThat(suspended.getStatus()).isEqualTo(RiderStatus.SUSPENDED);
		assertThat(suspended.isOnline()).isFalse();
		assertThat(subjectTo(r.getEmail())).isEqualTo("Your Zaika rider account is suspended");
		assertThat(mailTo(r.getEmail())).contains("Reason: Three late deliveries this week.");
		assertThat(detail(r)).contains("Reason given:").contains("Three late deliveries this week.").contains("Approve again");
		assertThat(html(get("/partner").with(as(r)))).contains("Your rider account is suspended.")
			.contains("Three late deliveries this week.");

		clearInvocations(mail);
		mvc.perform(decide(r, "approve")).andExpect(redirectedUrl("/admin/riders/" + r.getId()));
		assertThat(profile(r).getStatus()).isEqualTo(RiderStatus.APPROVED);
		assertThat(profile(r).getStatusReason()).isNull();
		assertThat(subjectTo(r.getEmail())).isEqualTo("You can ride with Zaika again");
	}

	@Test
	void aPendingApplicationCanBeSuspendedToo() throws Exception {
		User r = rider(word() + " Doubtful", RiderStatus.PENDING);
		mvc.perform(decide(r, "suspend").param("reason", "The number plate does not match the RC."))
			.andExpect(redirectedUrl("/admin/riders/" + r.getId()));
		assertThat(profile(r).getStatus()).isEqualTo(RiderStatus.SUSPENDED);
	}

	@Test
	void decisionsThatDoNotFitTheStatusAreRefusedWithoutMail() throws Exception {
		User approved = rider(word() + " Already", RiderStatus.APPROVED);
		mvc.perform(decide(approved, "approve"))
			.andExpect(flash().attribute("flashError", approved.getName() + " is already approved."));
		User suspended = rider(word() + " Benched", RiderStatus.SUSPENDED);
		mvc.perform(decide(suspended, "suspend").param("reason", "Again"))
			.andExpect(flash().attribute("flashError",
					suspended.getName() + " is already suspended. Approve them to let them ride again."));
		// a missing reason on a rider who is already suspended gets the refusal, not the form
		mvc.perform(decide(suspended, "suspend").param("reason", ""))
			.andExpect(redirectedUrl("/admin/riders/" + suspended.getId()))
			.andExpect(flash().attributeExists("flashError"));
		assertThat(profile(suspended).getStatusReason()).isEqualTo("Late deliveries");
		verify(mail, never()).send(anyString(), anyString(), anyString());
	}

	// ---------------------------------------------------------------- navigation and security

	@Test
	void theAdminNavAndTheDashboardLeadToPendingRiders() throws Exception {
		User admin = admin();
		String dashboard = html(get("/admin").with(as(admin)));
		assertThat(dashboard).contains("href=\"/admin/riders?status=pending\"").contains("Review riders")
			.contains("<a class=\"tab\" href=\"/admin/riders\"").contains("<a class=\"tab\" href=\"/admin/users\"");
	}

	@Test
	void onlyAdminsModerateRiders() throws Exception {
		User r = rider(word() + " Guarded", RiderStatus.PENDING);
		String detailUrl = "/admin/riders/" + r.getId();
		mvc.perform(get("/admin/riders")).andExpect(redirectedUrl("/login"));
		mvc.perform(get(detailUrl)).andExpect(redirectedUrl("/login"));
		mvc.perform(post(detailUrl + "/approve").with(csrf())).andExpect(redirectedUrl("/login"));

		String slug = "rider-guard-" + UUID.randomUUID().toString().substring(0, 8);
		fixtures.restaurant(slug, "Guard Kitchen").save();
		User owner = users.findByEmailIgnoreCase(slug + "@fixtures.zaika.test").orElseThrow();
		for (User notAdmin : List.of(orders.customer(), owner, r)) {
			mvc.perform(get("/admin/riders").with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(get(detailUrl).with(as(notAdmin))).andExpect(status().isForbidden());
			for (String action : List.of("approve", "suspend")) {
				mvc.perform(post(detailUrl + "/" + action).param("reason", "Mine").with(as(notAdmin)).with(csrf()))
					.andExpect(status().isForbidden());
			}
		}
		mvc.perform(post(detailUrl + "/approve").with(asAdmin())).andExpect(status().isForbidden());
		assertThat(profile(r).getStatus()).isEqualTo(RiderStatus.PENDING);
		verify(mail, never()).send(anyString(), anyString(), anyString());
		mvc.perform(get(detailUrl).with(asAdmin())).andExpect(view().name("admin/rider"));
	}

}
