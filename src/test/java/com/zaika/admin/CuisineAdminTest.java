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

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T41: cuisines in the admin panel ({@code /admin/cuisines}). The tests share one database, so each names its
 * cuisines with a unique word.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:admincuisines;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CuisineAdminTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	CuisineRepository cuisines;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	/** Letters only, so it reads like a name: "Qxbtkfwz". */
	static String word() {
		StringBuilder w = new StringBuilder("Q");
		UUID.randomUUID().toString().replace("-", "").chars().limit(9).forEach(c -> w.append((char) ('a' + c % 26)));
		return w.toString();
	}

	Restaurant restaurant(String cuisine, RestaurantStatus status) {
		return fixtures.restaurant("cui-" + UUID.randomUUID().toString().substring(0, 8), "Kitchen " + word())
			.cuisines(cuisine).status(status).save();
	}

	Cuisine cuisine(String name) {
		return cuisines.findByNameIgnoreCase(name).orElseThrow();
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

	String page() throws Exception {
		return html(get("/admin/cuisines"));
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.with(asAdmin()))
			.andExpect(status().isOk())
			.andExpect(view().name("admin/cuisines"))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	MockHttpServletRequestBuilder add(String name) {
		return post("/admin/cuisines").param("name", name).with(csrf());
	}

	MockHttpServletRequestBuilder rename(Cuisine c, String name) {
		return post("/admin/cuisines/" + c.getId() + "/rename").param("name", name).with(csrf());
	}

	/** The list item of one cuisine. */
	static String item(String page, Cuisine c) {
		int start = page.indexOf("<li class=\"admin-cuisine\" id=\"cuisine-" + c.getId() + "\"");
		assertThat(start).as("item of " + c.getName()).isPositive();
		return page.substring(start, page.indexOf("</li>", start));
	}

	@Test
	void theListShowsEveryCuisineByNameWithItsRestaurants() throws Exception {
		String w = word();
		Cuisine busy = fixtures.cuisine(w + " Bhakri");
		Cuisine quiet = fixtures.cuisine(w + " Amras");
		restaurant(busy.getName(), RestaurantStatus.APPROVED);
		restaurant(busy.getName(), RestaurantStatus.APPROVED);
		restaurant(busy.getName(), RestaurantStatus.PENDING);
		Cuisine single = fixtures.cuisine(w + " Chutney");
		restaurant(single.getName(), RestaurantStatus.APPROVED);

		String page = page();
		assertThat(page).contains("<title>Cuisines | Zaika</title>")
			.contains("<a class=\"tab\" href=\"/admin/cuisines\" aria-current=\"page\"")
			.containsSubsequence(quiet.getName(), busy.getName(), single.getName())
			.contains(cuisines.count() + " cuisines");
		assertThat(item(page, busy)).contains("3 restaurants · 2 live")
			.contains("/restaurants?cuisine=" + busy.getSlug())
			.contains("href=\"/restaurants?cuisine=" + busy.getSlug() + "\"")
			.contains("action=\"/admin/cuisines/" + busy.getId() + "/rename\"")
			.contains("3 restaurants list “" + busy.getName() + "”, so it can&#39;t be deleted. Rename it instead.")
			.doesNotContain("Yes, delete");
		assertThat(item(page, single)).contains("One restaurant · 1 live");
		assertThat(item(page, quiet)).contains("No restaurants yet").contains("Yes, delete")
			.contains("action=\"/admin/cuisines/" + quiet.getId() + "/delete\"")
			.doesNotContain("href=\"/restaurants?cuisine=");
	}

	@Test
	void theListRunsAFixedNumberOfQueries() throws Exception {
		RequestPostProcessor signedIn = asAdmin();
		mvc.perform(get("/admin/cuisines").with(signedIn)).andExpect(status().isOk()); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		mvc.perform(get("/admin/cuisines").with(signedIn)).andExpect(status().isOk());
		long before = stats.getPrepareStatementCount();
		for (int i = 0; i < 3; i++) {
			restaurant(fixtures.cuisine(word() + " Counted").getName(), RestaurantStatus.APPROVED);
		}
		stats.clear();
		mvc.perform(get("/admin/cuisines").with(signedIn)).andExpect(status().isOk());
		assertThat(stats.getPrepareStatementCount()).isEqualTo(before); // cuisines + one grouped count
	}

	@Test
	void addingACuisineMakesItAvailableWithASlug() throws Exception {
		String name = word() + "   Street  Food ";
		String stored = name.strip().replaceAll("\\s+", " ");
		mvc.perform(add(name).with(asAdmin()))
			.andExpect(redirectedUrl("/admin/cuisines"))
			.andExpect(flash().attribute("flashSuccess",
					stored + " is added. Restaurants can pick it on their profile now."));
		Cuisine saved = cuisine(stored);
		assertThat(saved.getSlug()).isEqualTo(stored.toLowerCase().replace(' ', '-'));
		assertThat(item(page(), saved)).contains(stored).contains("No restaurants yet");
	}

	@Test
	void newNamesAreValidatedWithTheInputKept() throws Exception {
		long before = cuisines.count();
		assertThat(html(add(" "))).contains("Name the cuisine, like Konkani.").contains("aria-invalid=\"true\"");
		assertThat(html(add("x".repeat(41)))).contains("Keep the name under 40 characters.");
		assertThat(html(add("&&&"))).contains("Use letters or digits in the name.");

		String w = word();
		Cuisine existing = fixtures.cuisine(w + " Kolhapuri");
		assertThat(html(add(existing.getName().toUpperCase())))
			.contains("There is already a cuisine called " + existing.getName() + ".")
			.contains("value=\"" + existing.getName().toUpperCase() + "\"");
		assertThat(html(add(w + "-Kolhapuri")))
			.contains("That name is too close to " + existing.getName() + ". Rename " + existing.getName()
					+ " instead, or pick another name.");
		assertThat(html(add("<b>" + w + "</b> " + "x".repeat(40)))).contains("&lt;b&gt;" + w + "&lt;/b&gt;")
			.doesNotContain("<b>" + w + "</b>");
		assertThat(cuisines.count()).isEqualTo(before + 1); // the fixture only

		mvc.perform(add("<i>" + w + "</i>").with(asAdmin())).andExpect(redirectedUrl("/admin/cuisines"));
		assertThat(page()).contains("&lt;i&gt;" + w + "&lt;/i&gt;").doesNotContain("<i>" + w + "</i>");
	}

	@Test
	void renamingKeepsTheLink() throws Exception {
		String w = word();
		Cuisine c = fixtures.cuisine(w + " Thali");
		restaurant(c.getName(), RestaurantStatus.APPROVED);
		mvc.perform(rename(c, w + " Thalis").with(asAdmin()))
			.andExpect(redirectedUrl("/admin/cuisines#cuisine-" + c.getId()))
			.andExpect(flash().attribute("flashSuccess", c.getName() + " is now called " + w + " Thalis."));
		Cuisine renamed = cuisines.findById(c.getId()).orElseThrow();
		assertThat(renamed.getName()).isEqualTo(w + " Thalis");
		assertThat(renamed.getSlug()).isEqualTo(c.getSlug());
		assertThat(item(page(), renamed)).contains("The link stays /restaurants?cuisine=" + c.getSlug() + ".");

		mvc.perform(rename(renamed, (w + " THALIS")).with(asAdmin())).andExpect(redirectedUrl("/admin/cuisines#cuisine-" + c.getId()));
		assertThat(cuisines.findById(c.getId()).orElseThrow().getName()).as("own name, other case").isEqualTo(w + " THALIS");
	}

	@Test
	void aRenameThatFailsReopensThatFormWithTheError() throws Exception {
		String w = word();
		Cuisine a = fixtures.cuisine(w + " Misal");
		Cuisine b = fixtures.cuisine(w + " Usal");
		String page = html(rename(a, b.getName().toLowerCase()));
		assertThat(item(page, a)).contains("<details class=\"menu-admin-pop-wrap\" data-menu open=\"open\">")
			.contains("There is already a cuisine called " + b.getName() + ".")
			.contains("value=\"" + b.getName().toLowerCase() + "\"");
		assertThat(item(page, b)).doesNotContain("open=\"open\"");
		assertThat(item(html(rename(a, "")), a)).contains("Name the cuisine, like Konkani.");
		assertThat(cuisines.findById(a.getId()).orElseThrow().getName()).isEqualTo(a.getName());
	}

	@Test
	void onlyACuisineNoRestaurantListsCanBeDeleted() throws Exception {
		String w = word();
		Cuisine unused = fixtures.cuisine(w + " Modak");
		mvc.perform(post("/admin/cuisines/" + unused.getId() + "/delete").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/cuisines"))
			.andExpect(flash().attribute("flashSuccess", unused.getName() + " is deleted."));
		assertThat(cuisines.findById(unused.getId())).isEmpty();

		Cuisine used = fixtures.cuisine(w + " Puran Poli");
		restaurant(used.getName(), RestaurantStatus.SUSPENDED);
		mvc.perform(post("/admin/cuisines/" + used.getId() + "/delete").with(asAdmin()).with(csrf()))
			.andExpect(redirectedUrl("/admin/cuisines"))
			.andExpect(flash().attribute("flashError", used.getName()
					+ " is listed by one restaurant. It can only be deleted once no restaurant lists it. Rename it instead."));
		assertThat(cuisines.findById(used.getId())).isPresent();
	}

	@Test
	void unknownCuisinesAre404() throws Exception {
		mvc.perform(post("/admin/cuisines/999999/rename").param("name", "Fine Name").with(asAdmin()).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/admin/cuisines/999999/rename").param("name", "").with(asAdmin()).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/admin/cuisines/999999/delete").with(asAdmin()).with(csrf())).andExpect(status().isNotFound());
	}

	@Test
	void onlyAdminsManageCuisines() throws Exception {
		Cuisine c = fixtures.cuisine(word() + " Guarded");
		String base = "/admin/cuisines/" + c.getId();
		mvc.perform(get("/admin/cuisines")).andExpect(redirectedUrl("/login"));
		mvc.perform(add("Sneaky")).andExpect(redirectedUrl("/login"));
		mvc.perform(post(base + "/delete").with(csrf())).andExpect(redirectedUrl("/login"));

		Restaurant kitchen = restaurant(c.getName(), RestaurantStatus.APPROVED);
		User owner = users.findByEmailIgnoreCase(kitchen.getSlug() + "@fixtures.zaika.test").orElseThrow();
		for (User notAdmin : List.of(orders.customer(), owner, orders.rider())) {
			mvc.perform(get("/admin/cuisines").with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(add("Sneaky").with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(rename(c, "Sneaky").with(as(notAdmin))).andExpect(status().isForbidden());
			mvc.perform(post(base + "/delete").with(as(notAdmin)).with(csrf())).andExpect(status().isForbidden());
		}
		mvc.perform(post("/admin/cuisines").param("name", "Sneaky").with(asAdmin())).andExpect(status().isForbidden());
		assertThat(cuisines.findByNameIgnoreCase("Sneaky")).isEmpty();
		assertThat(cuisines.findById(c.getId()).orElseThrow().getName()).isEqualTo(c.getName());
	}

}
