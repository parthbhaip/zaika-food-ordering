package com.zaika.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.storage.StorageService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T30: the owner's menu at {@code /owner/menu}: categories (add, rename, reorder, delete), dishes (add and edit with
 * a photo, price, veg, bestseller, spice level, move to another category), the sold-out switch (plain form and
 * htmx), archiving dishes that were ever ordered, the pure-veg rule, and that every id belonging to another restaurant
 * is a 404.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ownermenu;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MenuManagementTest {

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13 };

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@MockitoSpyBean
	OrderRepository orderRepository;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@MockitoSpyBean
	StorageService storage;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	org.springframework.transaction.support.TransactionTemplate transactions;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	/** A restaurant with "Menu" (Mutton Thali ₹250 non-veg, Sweet Lassi ₹60 veg) and an empty "Desserts". */
	record Kitchen(Restaurant restaurant, MenuCategory main, MenuCategory desserts, MenuItem thali, MenuItem lassi) {

		RequestPostProcessor owner(ZaikaUserDetailsService userDetails) {
			return user(userDetails.loadUserByUsername(restaurant.getOwner().getEmail()));
		}

	}

	Kitchen kitchen(CatalogFixtures.Builder builder) {
		Restaurant r = builder.save();
		MenuCategory main = categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()).get(0);
		MenuCategory desserts = categories.save(new MenuCategory(r, "Desserts", 2));
		MenuItem thali = new MenuItem(main, "Mutton Thali", new BigDecimal("250"), r.isPureVeg() ? true : false);
		thali.setSortOrder(1);
		MenuItem lassi = new MenuItem(main, "Sweet Lassi", new BigDecimal("60"), true);
		lassi.setSortOrder(2);
		return new Kitchen(r, main, desserts, items.save(thali), items.save(lassi));
	}

	Kitchen kitchen() {
		return kitchen(fixtures.restaurant(slug(), "Menu Kitchen"));
	}

	static String slug() {
		return "menu-" + UUID.randomUUID().toString().substring(0, 8);
	}

	// ---------------------------------------------------------------- the menu page

	@Test
	void theMenuPageListsLiveCategoriesAndDishesInOrder() throws Exception {
		Kitchen k = kitchen();
		k.lassi().setAvailable(false);
		items.save(k.lassi());
		MenuItem gone = new MenuItem(k.main(), "Old Kheer", new BigDecimal("90"), true);
		gone.setArchived(true);
		items.save(gone);
		MenuCategory hidden = new MenuCategory(k.restaurant(), "Retired", 3);
		hidden.setArchived(true);
		categories.save(hidden);
		Kitchen other = kitchen();

		String html = html(get("/owner/menu").with(k.owner(userDetails)));
		assertThat(html).contains("<title>Menu | Zaika</title>")
			.contains("<a class=\"tab\" href=\"/owner/menu\" aria-current=\"page\">")
			.contains("<a class=\"tab\" href=\"/owner\">")
			.contains("<h1 class=\"owner-title\">Your menu</h1>")
			.contains("<span>2 dishes in 2 categories</span> <span class=\"badge\">1 sold out</span>")
			.contains("<h2 class=\"owner-section-title\" id=\"category-" + k.main().getId() + "-title\">Menu</h2>")
			.contains("2 dishes · 1 sold out", "No dishes yet")
			.contains("<li class=\"menu-admin-dish\" id=\"dish-" + k.thali().getId() + "\">")
			.contains("<li class=\"menu-admin-dish is-unavailable\" id=\"dish-" + k.lassi().getId() + "\">")
			.contains("aria-checked=\"false\" aria-label=\"Sweet Lassi in stock\"></button> <span class=\"menu-admin-stock-label\" aria-hidden=\"true\">Sold out</span>")
			.contains("href=\"/owner/menu/items/new?category=" + k.desserts().getId() + "\"")
			.contains("“Menu” still has 2 dishes. Move them to another category or delete them first.")
			.contains("Delete the “Desserts” category?")
			.doesNotContain("Old Kheer", "Retired", "dish-" + other.thali().getId())
			.doesNotContain("Waiting for review"); // approved: no banner
		// menu order: Menu before Desserts, thali before lassi; Menu cannot move up, Desserts cannot move down
		assertThat(html.indexOf("category-" + k.main().getId() + "-title"))
			.isLessThan(html.indexOf("category-" + k.desserts().getId() + "-title"));
		assertThat(html.indexOf("dish-" + k.thali().getId())).isLessThan(html.indexOf("dish-" + k.lassi().getId()));
		assertThat(html).contains("aria-label=\"Move Menu up\" disabled=\"disabled\"", "aria-label=\"Move Desserts down\" disabled=\"disabled\"")
			.doesNotContain("aria-label=\"Move Menu down\" disabled", "aria-label=\"Move Desserts up\" disabled");
		// the dashboard links here
		assertThat(html(get("/owner").with(k.owner(userDetails)))).contains("<a class=\"tab\" href=\"/owner/menu\">");
	}

	@Test
	void aRestaurantWithoutCategoriesGetsAFriendlyStart() throws Exception {
		Restaurant r = fixtures.restaurant(slug(), "Blank Kitchen").save();
		categories.deleteAll(categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId()));
		RequestPostProcessor owner = user(userDetails.loadUserByUsername(r.getOwner().getEmail()));

		assertThat(html(get("/owner/menu").with(owner))).contains("Start your menu", "Add your first category")
			.doesNotContain("Add a dish", "dishes in");
		mvc.perform(get("/owner/menu/items/new").with(owner))
			.andExpect(redirectedUrl("/owner/menu"))
			.andExpect(flash().attribute("flashInfo", "Add a category first, like Starters or Thalis. Dishes go in it."));
	}

	@Test
	void aPendingRestaurantSeesItsReviewBannerOnTheMenuPages() throws Exception {
		Kitchen k = kitchen(fixtures.restaurant(slug(), "Pending Kitchen").status(RestaurantStatus.PENDING));
		assertThat(html(get("/owner/menu").with(k.owner(userDetails)))).contains("Waiting for review.");
		assertThat(html(get("/owner/menu/items/new").with(k.owner(userDetails)))).contains("Waiting for review.");
	}

	@Test
	void theMenuPageRunsAFixedNumberOfQueries() throws Exception {
		Kitchen few = kitchen();
		Kitchen many = kitchen();
		for (int i = 0; i < 12; i++) {
			MenuItem item = new MenuItem(i % 2 == 0 ? many.main() : many.desserts(), "Dish " + i, new BigDecimal("99"), true);
			item.setImagePath(i % 3 == 0 ? "dish/x" + i + ".png" : null);
			items.save(item);
		}
		html(get("/owner/menu").with(few.owner(userDetails))); // warm up
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		html(get("/owner/menu").with(few.owner(userDetails)));
		long one = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/owner/menu").with(many.owner(userDetails)));
		// restaurant, categories, dishes, the header bell's unread count
		assertThat(stats.getPrepareStatementCount()).isEqualTo(one).isLessThanOrEqualTo(5);
	}

	// ---------------------------------------------------------------- categories

	@Test
	void anOwnerAddsACategoryAtTheEnd() throws Exception {
		Kitchen k = kitchen();
		String target = mvc.perform(post("/owner/menu/categories").param("name", "  Breads  ").with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("flashSuccess", "“Breads” added. Now add its dishes."))
			.andReturn().getResponse().getRedirectedUrl();
		List<MenuCategory> all = categories.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId());
		assertThat(all).extracting(MenuCategory::getName).containsExactly("Menu", "Desserts", "Breads");
		MenuCategory breads = all.get(2);
		assertThat(breads.getSortOrder()).isEqualTo(3);
		assertThat(target).isEqualTo("/owner/menu#category-" + breads.getId());
		assertThat(html(get("/owner/menu").with(k.owner(userDetails)))).contains(">Breads</h2>", "No dishes yet");
	}

	@Test
	void categoryNamesAreCheckedAndTypedInputKept() throws Exception {
		Kitchen k = kitchen();
		assertThat(html(post("/owner/menu/categories").param("name", " ").with(k.owner(userDetails)).with(csrf())))
			.contains("Give the category a name, like Starters.", "aria-invalid=\"true\"");
		assertThat(html(post("/owner/menu/categories").param("name", "x".repeat(61)).with(k.owner(userDetails)).with(csrf())))
			.contains("Keep the name under 60 characters.", "value=\"" + "x".repeat(61) + "\"");
		mvc.perform(post("/owner/menu/categories").param("name", "<b>Sweets</b>").with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(html(get("/owner/menu").with(k.owner(userDetails)))).contains("&lt;b&gt;Sweets&lt;/b&gt;").doesNotContain("<b>Sweets</b>");
		assertThat(html(post("/owner/menu/categories").param("name", "DESSERTS").with(k.owner(userDetails)).with(csrf())))
			.contains("You already have a category called “DESSERTS”.", "value=\"DESSERTS\"");
		assertThat(categories.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId())).hasSize(3);
	}

	@Test
	void anOwnerRenamesACategory() throws Exception {
		Kitchen k = kitchen();
		long id = k.desserts().getId();
		mvc.perform(post("/owner/menu/categories/{id}/rename", id).param("name", "Sweets").with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu#category-" + id))
			.andExpect(flash().attribute("flashSuccess", "Category renamed to “Sweets”."));
		assertThat(categories.findById(id).orElseThrow().getName()).isEqualTo("Sweets");

		// an error re-renders the page with that rename form open, the typed name kept and the message beside it
		String html = html(post("/owner/menu/categories/{id}/rename", id).param("name", "menu").with(k.owner(userDetails)).with(csrf()));
		assertThat(html).contains("<details class=\"menu-admin-pop-wrap\" data-menu open=\"open\">")
			.contains("id=\"rename-" + id + "\" name=\"name\" maxlength=\"60\" autocomplete=\"off\" value=\"menu\" aria-invalid=\"true\"")
			.contains("You already have a category called “menu”.")
			.contains("id=\"rename-" + k.main().getId() + "\" name=\"name\" maxlength=\"60\" autocomplete=\"off\" value=\"Menu\">");
		assertThat(categories.findById(id).orElseThrow().getName()).isEqualTo("Sweets");
	}

	@Test
	void categoriesMoveUpAndDown() throws Exception {
		Kitchen k = kitchen();
		MenuCategory drinks = categories.save(new MenuCategory(k.restaurant(), "Drinks", 7));
		long rid = k.restaurant().getId();

		mvc.perform(post("/owner/menu/categories/{id}/move", drinks.getId()).param("direction", "UP").with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu#category-" + drinks.getId()))
			.andExpect(flash().attribute("flashSuccess", "“Drinks” moved up."));
		assertThat(categories.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(rid))
			.extracting(MenuCategory::getName, MenuCategory::getSortOrder)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("Menu", 1), org.assertj.core.groups.Tuple.tuple("Drinks", 2),
					org.assertj.core.groups.Tuple.tuple("Desserts", 3));

		mvc.perform(post("/owner/menu/categories/{id}/move", k.main().getId()).param("direction", "DOWN").with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		mvc.perform(post("/owner/menu/categories/{id}/move", k.desserts().getId()).param("direction", "DOWN").with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().is3xxRedirection()); // already last: nothing changes
		assertThat(categories.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(rid)).extracting(MenuCategory::getName)
			.containsExactly("Drinks", "Menu", "Desserts");
		String html = html(get("/owner/menu").with(k.owner(userDetails)));
		assertThat(html.indexOf(">Drinks</h2>")).isPositive().isLessThan(html.indexOf(">Menu</h2>"));
		assertThat(html.indexOf(">Menu</h2>")).isLessThan(html.indexOf(">Desserts</h2>"));

		mvc.perform(post("/owner/menu/categories/{id}/move", drinks.getId()).param("direction", "sideways").with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void anEmptyCategoryIsDeletedAndOneWithDishesIsRefused() throws Exception {
		Kitchen k = kitchen();
		mvc.perform(post("/owner/menu/categories/{id}/delete", k.main().getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu#category-" + k.main().getId()))
			.andExpect(flash().attribute("flashError", "Move or delete the 2 dishes in “Menu” first, then delete the category."));
		assertThat(categories.findById(k.main().getId())).isPresent();

		mvc.perform(post("/owner/menu/categories/{id}/delete", k.desserts().getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu"))
			.andExpect(flash().attribute("flashSuccess", "“Desserts” deleted."));
		assertThat(categories.findById(k.desserts().getId())).isEmpty();
	}

	@Test
	void aCategoryHoldingOnlyArchivedDishesIsArchived() throws Exception {
		Kitchen k = kitchen();
		MenuItem kheer = new MenuItem(k.desserts(), "Kheer", new BigDecimal("90"), true);
		kheer.setArchived(true);
		items.save(kheer);
		mvc.perform(post("/owner/menu/categories/{id}/delete", k.desserts().getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "“Desserts” deleted."));
		assertThat(categories.findById(k.desserts().getId()).orElseThrow().isArchived()).isTrue();
		assertThat(html(get("/owner/menu").with(k.owner(userDetails)))).doesNotContain(">Desserts</h2>");
		// archived: the name can be used again
		mvc.perform(post("/owner/menu/categories").param("name", "Desserts").with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().is3xxRedirection());
	}

	@Test
	void anotherOwnersCategoriesAreNotFound() throws Exception {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		long id = theirs.desserts().getId();
		RequestPostProcessor me = mine.owner(userDetails);
		mvc.perform(post("/owner/menu/categories/{id}/rename", id).param("name", "Mine now").with(me).with(csrf())).andExpect(status().isNotFound());
		// an invalid name does not turn the foreign id into a page of the caller's own menu
		mvc.perform(post("/owner/menu/categories/{id}/rename", id).param("name", " ").with(me).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/owner/menu/categories/{id}/move", id).param("direction", "UP").with(me).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/owner/menu/categories/{id}/delete", id).with(me).with(csrf())).andExpect(status().isNotFound());
		assertThat(categories.findById(id).orElseThrow().getName()).isEqualTo("Desserts");
		assertThat(categories.findById(id).orElseThrow().getSortOrder()).isEqualTo(2);
	}

	// ---------------------------------------------------------------- adding and editing dishes

	@Test
	void theAddDishFormHasEveryField() throws Exception {
		Kitchen k = kitchen();
		String html = html(get("/owner/menu/items/new").param("category", k.desserts().getId().toString()).with(k.owner(userDetails)));
		assertThat(html).contains("<title>Add a dish | Zaika</title>", "<h1 id=\"dish-form-title\" class=\"owner-title\">Add a dish</h1>")
			.contains("action=\"/owner/menu/items/new\" method=\"post\" enctype=\"multipart/form-data\"")
			.contains("<option value=\"" + k.desserts().getId() + "\" selected=\"selected\">Desserts</option>")
			.contains("<option value=\"" + k.main().getId() + "\">Menu</option>")
			.contains("name=\"name\"", "name=\"description\"", "name=\"price\"", "inputmode=\"decimal\"")
			.contains("<option value=\"0\" selected=\"selected\">Not spicy</option>", "<option value=\"3\">Very spicy</option>")
			.contains("type=\"radio\" value=\"true\" id=\"veg1\" name=\"veg\">", "type=\"radio\" value=\"false\" id=\"veg2\" name=\"veg\">")
			.contains("role=\"switch\" id=\"available1\" name=\"available\" value=\"true\" checked=\"checked\">")
			.contains("<input type=\"hidden\" name=\"_available\" value=\"on\"/>", "name=\"bestseller\"")
			.contains("type=\"file\" id=\"image\" name=\"image\" accept=\"image/jpeg,image/png,image/webp\"")
			.contains("Add to menu", "href=\"/owner/menu\">Cancel</a>")
			.doesNotContain("Remove this photo");
	}

	@Test
	void anOwnerAddsADishWithAPhoto() throws Exception {
		Kitchen k = kitchen();
		mvc.perform(dishForm("/owner/menu/items/new", k.desserts(), "Ukadiche Modak", "120.5", "true")
				.file(new MockMultipartFile("image", "modak.png", "image/png", PNG))
				.param("description", "Steamed, with jaggery and coconut.")
				.param("bestseller", "true").param("spiceLevel", "0")
				.with(k.owner(userDetails)))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("flashSuccess", "“Ukadiche Modak” is on your menu."));
		MenuItem modak = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId()).getLast();
		assertThat(modak.getName()).isEqualTo("Ukadiche Modak");
		assertThat(modak.getCategory().getId()).isEqualTo(k.desserts().getId());
		assertThat(modak.getPrice()).isEqualByComparingTo("120.50");
		assertThat(modak.isVeg()).isTrue();
		assertThat(modak.isBestseller()).isTrue();
		assertThat(modak.isAvailable()).isTrue();
		assertThat(modak.getDescription()).isEqualTo("Steamed, with jaggery and coconut.");
		assertThat(modak.getSortOrder()).isGreaterThan(k.lassi().getSortOrder());
		assertThat(modak.getImagePath()).startsWith("dish/").endsWith(".png");
		assertThat(storage.root().resolve(modak.getImagePath())).exists();
		// customers see it
		assertThat(html(get("/r/{slug}", k.restaurant().getSlug()))).contains("Ukadiche Modak", "/uploads/" + modak.getImagePath());
	}

	@Test
	void aSoldOutSpicyNonVegDishWithoutAPhotoCanBeAdded() throws Exception {
		Kitchen k = kitchen();
		mvc.perform(dishForm("/owner/menu/items/new", k.main(), "Tambda Rassa", "80", "false")
				.param("spiceLevel", "3").param("_available", "on").param("_bestseller", "on")
				.with(k.owner(userDetails)))
			.andExpect(status().is3xxRedirection());
		MenuItem rassa = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId()).getLast();
		assertThat(rassa.isVeg()).isFalse();
		assertThat(rassa.getSpiceLevel()).isEqualTo(3);
		assertThat(rassa.isAvailable()).isFalse(); // the switch was off: only its marker was sent
		assertThat(rassa.isBestseller()).isFalse();
		assertThat(rassa.getImagePath()).isNull();
		assertThat(html(get("/owner/menu").with(k.owner(userDetails))))
			.contains("<span class=\"badge badge-danger\">Very spicy</span>", "veg-mark non-veg");
	}

	@Test
	void dishErrorsShowBesideTheFieldsAndKeepTheInput() throws Exception {
		Kitchen k = kitchen();
		String html = html(dishForm("/owner/menu/items/new", k.main(), " ", "12.345", null)
			.param("description", "d".repeat(301)).param("spiceLevel", "5")
			.file(new MockMultipartFile("image", "modak.png", "image/png", PNG))
			.with(k.owner(userDetails)));
		assertThat(html).contains("A few details need another look.", "Attach the photo again too.")
			.contains("Give the dish a name.", "Use rupees and up to 2 decimals, like 180 or 179.50.")
			.contains("Keep the description under 300 characters.", "Choose a spice level.", "Choose veg or non-veg.")
			.contains("value=\"12.345\"", "d".repeat(301) + "</textarea>");
		assertThat(html(dishForm("/owner/menu/items/new", k.main(), "Thali", "abc", "true").with(k.owner(userDetails))))
			.contains("Enter an amount in rupees, like 250.");
		assertThat(html(dishForm("/owner/menu/items/new", k.main(), "Thali", "0", "true").with(k.owner(userDetails))))
			.contains("The price should be at least ₹1.");
		assertThat(html(dishForm("/owner/menu/items/new", k.main(), "<script>x</script>", "10001", "true").with(k.owner(userDetails))))
			.contains("The price can be at most ₹10,000.", "&lt;script&gt;x&lt;/script&gt;")
			.doesNotContain("<script>x</script>");
		assertThat(items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId())).hasSize(2);
	}

	@Test
	void anUnusablePhotoIsRefusedOnItsField() throws Exception {
		Kitchen k = kitchen();
		String html = html(dishForm("/owner/menu/items/new", k.main(), "Misal", "70", "true")
			.file(new MockMultipartFile("image", "menu.pdf", "image/png", "%PDF-1.7".getBytes()))
			.with(k.owner(userDetails)));
		assertThat(html).contains("id=\"image-error\"", "Choose a JPG, PNG or WebP image.", "value=\"Misal\"");
		assertThat(items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId())).hasSize(2);
	}

	@Test
	void aPureVegKitchenCannotListNonVegDishes() throws Exception {
		Kitchen k = kitchen(fixtures.restaurant(slug(), "Green Leaf").pureVeg());
		String form = html(get("/owner/menu/items/new").with(k.owner(userDetails)));
		assertThat(form).contains("<input type=\"hidden\" name=\"veg\" value=\"true\">", "Green Leaf is a pure veg kitchen.")
			.doesNotContain("type=\"radio\"");
		assertThat(html(get("/owner/menu").with(k.owner(userDetails)))).contains("Pure veg kitchen: every dish you add is listed as veg.");

		String html = html(dishForm("/owner/menu/items/new", k.main(), "Chicken Sukka", "220", "false").with(k.owner(userDetails)));
		assertThat(html).contains("Green Leaf is listed as pure veg, so every dish must be veg.", "id=\"veg-error\"");
		html = html(dishForm("/owner/menu/items/" + k.thali().getId(), k.main(), "Veg Thali", "250", "false").with(k.owner(userDetails)));
		assertThat(html).contains("Green Leaf is listed as pure veg, so every dish must be veg.");
		assertThat(items.findById(k.thali().getId()).orElseThrow().isVeg()).isTrue();
		assertThat(items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(k.restaurant().getId())).hasSize(2);
	}

	@Test
	void theEditFormShowsTheDishAsItIs() throws Exception {
		Kitchen k = kitchen();
		MenuItem thali = k.thali();
		thali.setDescription("Sukka, tambda and pandhra rassa.");
		thali.setSpiceLevel(2);
		thali.setBestseller(true);
		thali.setImagePath("dish/thali.png");
		items.save(thali);
		String html = html(get("/owner/menu/items/{id}", thali.getId()).with(k.owner(userDetails)));
		assertThat(html).contains("<title>Edit dish | Zaika</title>", ">Edit Mutton Thali</h1>")
			.contains("action=\"/owner/menu/items/" + thali.getId() + "\" method=\"post\" enctype=\"multipart/form-data\"")
			.contains("<option value=\"" + k.main().getId() + "\" selected=\"selected\">Menu</option>")
			.contains("value=\"Mutton Thali\"", "value=\"250.00\"", "Sukka, tambda and pandhra rassa.</textarea>")
			.contains("<option value=\"2\" selected=\"selected\">Spicy</option>")
			.contains("value=\"false\" id=\"veg2\" name=\"veg\" checked=\"checked\">")
			.contains("id=\"bestseller1\" name=\"bestseller\" value=\"true\" checked=\"checked\">")
			.contains("src=\"/uploads/dish/thali.png\" alt=\"Current photo of Mutton Thali\"", "Remove this photo")
			.contains("Save changes");
	}

	@Test
	void anOwnerEditsADishAndMovesItToAnotherCategory() throws Exception {
		Kitchen k = kitchen();
		long id = k.lassi().getId();
		mvc.perform(dishForm("/owner/menu/items/" + id, k.desserts(), "Mango Lassi", "75", "true")
				.param("bestseller", "true").param("spiceLevel", "0").param("available", "true")
				.with(k.owner(userDetails)))
			.andExpect(redirectedUrl("/owner/menu#dish-" + id))
			.andExpect(flash().attribute("flashSuccess", "“Mango Lassi” saved."));
		MenuItem lassi = items.findById(id).orElseThrow();
		assertThat(lassi.getName()).isEqualTo("Mango Lassi");
		assertThat(lassi.getPrice()).isEqualByComparingTo("75.00");
		assertThat(lassi.isBestseller()).isTrue();
		assertThat(lassi.getDescription()).isNull();
		assertThat(jdbc.queryForObject("select category_id from menu_items where id = ?", Long.class, id))
			.isEqualTo(k.desserts().getId());
		assertThat(lassi.getSortOrder()).isGreaterThan(k.thali().getSortOrder());
	}

	@Test
	void aNewPhotoReplacesTheOldOneAndAPhotoCanBeRemoved() throws Exception {
		Kitchen k = kitchen();
		long id = k.thali().getId();
		mvc.perform(dishForm("/owner/menu/items/" + id, k.main(), "Mutton Thali", "250", "false")
				.file(new MockMultipartFile("image", "a.png", "image/png", PNG)).with(k.owner(userDetails)))
			.andExpect(status().is3xxRedirection());
		String first = items.findById(id).orElseThrow().getImagePath();
		Path firstFile = storage.root().resolve(first);
		assertThat(firstFile).exists();

		mvc.perform(dishForm("/owner/menu/items/" + id, k.main(), "Mutton Thali", "250", "false")
				.file(new MockMultipartFile("image", "b.png", "image/png", PNG)).with(k.owner(userDetails)))
			.andExpect(status().is3xxRedirection());
		String second = items.findById(id).orElseThrow().getImagePath();
		assertThat(second).isNotEqualTo(first);
		assertThat(storage.root().resolve(second)).exists();
		assertThat(firstFile).doesNotExist();

		mvc.perform(dishForm("/owner/menu/items/" + id, k.main(), "Mutton Thali", "250", "false")
				.param("removeImage", "true").with(k.owner(userDetails)))
			.andExpect(status().is3xxRedirection());
		assertThat(items.findById(id).orElseThrow().getImagePath()).isNull();
		assertThat(storage.root().resolve(second)).doesNotExist();

		// a rejected change stores no photo
		long files;
		try (var listing = java.nio.file.Files.list(storage.root().resolve("dish"))) {
			files = listing.count();
		}
		Kitchen other = kitchen();
		html(dishForm("/owner/menu/items/" + id, other.main(), "Mutton Thali", "250", "false")
			.file(new MockMultipartFile("image", "d.png", "image/png", PNG)).with(k.owner(userDetails)));
		assertThat(items.findById(id).orElseThrow().getImagePath()).isNull();
		try (var listing = java.nio.file.Files.list(storage.root().resolve("dish"))) {
			assertThat(listing.count()).isEqualTo(files);
		}
	}

	@Test
	void aDishCannotBeMovedIntoAnotherRestaurantsCategory() throws Exception {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		assertThat(html(dishForm("/owner/menu/items/" + mine.thali().getId(), theirs.main(), "Mutton Thali", "250", "false")
			.with(mine.owner(userDetails)))).contains("Choose one of your categories.");
		assertThat(html(dishForm("/owner/menu/items/new", theirs.main(), "Sneaky", "50", "true")
			.with(mine.owner(userDetails)))).contains("Choose one of your categories.");
		assertThat(jdbc.queryForObject("select category_id from menu_items where id = ?", Long.class, mine.thali().getId()))
			.isEqualTo(mine.main().getId());
		assertThat(items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(theirs.restaurant().getId())).hasSize(2);
	}

	@Test
	void theDishMoveRuleLivesOnTheEntityToo() {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		transactions.executeWithoutResult(tx -> {
			MenuItem thali = items.findById(mine.thali().getId()).orElseThrow();
			MenuCategory foreign = categories.findById(theirs.main().getId()).orElseThrow();
			org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> thali.moveTo(foreign));
			thali.moveTo(categories.findById(mine.desserts().getId()).orElseThrow());
			assertThat(thali.getCategory().getId()).isEqualTo(mine.desserts().getId());
			tx.setRollbackOnly();
		});
	}

	// ---------------------------------------------------------------- sold out switch

	@Test
	void theStockSwitchWorksAsAPlainForm() throws Exception {
		Kitchen k = kitchen();
		long id = k.thali().getId();
		mvc.perform(post("/owner/menu/items/{id}/availability", id).param("available", "false").with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu#dish-" + id))
			.andExpect(flash().attribute("flashInfo", "“Mutton Thali” marked sold out. Customers see it greyed out."));
		assertThat(items.findById(id).orElseThrow().isAvailable()).isFalse();
		assertThat(html(get("/r/{slug}", k.restaurant().getSlug()))).contains("Sold out today");

		mvc.perform(post("/owner/menu/items/{id}/availability", id).param("available", "true").with(k.owner(userDetails)).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "“Mutton Thali” is back in stock."));
		assertThat(items.findById(id).orElseThrow().isAvailable()).isTrue();
	}

	@Test
	void theStockSwitchSwapsItsRowWithHtmx() throws Exception {
		Kitchen k = kitchen();
		long id = k.thali().getId();
		String html = html(post("/owner/menu/items/{id}/availability", id).param("available", "false")
			.header("HX-Request", "true").with(k.owner(userDetails)).with(csrf()));
		assertThat(html.strip()).startsWith("<li class=\"menu-admin-dish is-unavailable\" id=\"dish-" + id + "\">")
			.contains("hx-post=\"/owner/menu/items/" + id + "/availability\" hx-target=\"#dish-" + id + "\" hx-swap=\"outerHTML\"")
			.contains("<input type=\"hidden\" name=\"available\" value=\"true\">")
			.contains("id=\"stock-" + id + "\" aria-checked=\"false\" aria-label=\"Mutton Thali in stock\"")
			.contains("<div hx-swap-oob=\"beforeend:#toasts\">", "“Mutton Thali” marked sold out.")
			.doesNotContain("<html", "site-header");
		assertThat(items.findById(id).orElseThrow().isAvailable()).isFalse();
		mvc.perform(post("/owner/menu/items/{id}/availability", id).with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- deleting dishes

	@Test
	void aDishNeverOrderedIsDeletedWithItsPhotoAndLeavesCarts() throws Exception {
		Kitchen k = kitchen();
		MenuItem lassi = k.lassi();
		lassi.setImagePath(storage.store(com.zaika.storage.ImageKind.DISH, new MockMultipartFile("image", "l.png", "image/png", PNG)));
		items.save(lassi);
		Path photo = storage.root().resolve(lassi.getImagePath());
		User customer = orders.customer();
		mvc.perform(post("/cart/items/{id}", lassi.getId()).param("quantity", "2")
				.with(user(userDetails.loadUserByUsername(customer.getEmail()))).with(csrf()))
			.andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForObject("select count(*) from cart_items where menu_item_id = ?", Long.class, lassi.getId())).isOne();

		mvc.perform(post("/owner/menu/items/{id}/delete", lassi.getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu"))
			.andExpect(flash().attribute("flashSuccess", "“Sweet Lassi” deleted."));
		assertThat(items.findById(lassi.getId())).isEmpty();
		assertThat(photo).doesNotExist();
		assertThat(jdbc.queryForObject("select count(*) from cart_items where menu_item_id = ?", Long.class, lassi.getId())).isZero();
	}

	@Test
	void aDishThatWasEverOrderedIsArchivedNotDeleted() throws Exception {
		Kitchen k = kitchen();
		Order order = orders.placeThenCancel(orders.customer().getId(), k.thali().getId(), 1, PaymentMethod.COD);
		mvc.perform(post("/owner/menu/items/{id}/delete", k.thali().getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "“Mutton Thali” is off your menu. Past orders still show it."));
		MenuItem thali = items.findById(k.thali().getId()).orElseThrow();
		assertThat(thali.isArchived()).isTrue();
		assertThat(jdbc.queryForObject("select menu_item_id from order_items where order_id = ?", Long.class, order.getId()))
			.isEqualTo(thali.getId());
		assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(html(get("/owner/menu").with(k.owner(userDetails)))).doesNotContain("Mutton Thali").contains("1 dish in 2 categories");
		assertThat(html(get("/r/{slug}", k.restaurant().getSlug()))).doesNotContain("Mutton Thali");
		// archived dishes are gone for the owner too
		mvc.perform(get("/owner/menu/items/{id}", thali.getId()).with(k.owner(userDetails))).andExpect(status().isNotFound());
		mvc.perform(post("/owner/menu/items/{id}/availability", thali.getId()).param("available", "true")
				.with(k.owner(userDetails)).with(csrf()))
			.andExpect(status().isNotFound());
		// the category now holds only an archived dish plus the lassi: still refused while the lassi is listed
		mvc.perform(post("/owner/menu/categories/{id}/delete", k.main().getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(flash().attribute("flashError", "Move or delete the dish in “Menu” first, then delete the category."));
	}

	@Test
	void aDishOrderedWhileItIsBeingDeletedIsArchivedInstead() throws Exception {
		Kitchen k = kitchen();
		Order order = orders.place(orders.customer().getId(), k.thali().getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		// the order lands between the "ever ordered?" check and the delete
		doReturn(false).when(orderRepository).wasEverOrdered(k.thali().getId());
		mvc.perform(post("/owner/menu/items/{id}/delete", k.thali().getId()).with(k.owner(userDetails)).with(csrf()))
			.andExpect(redirectedUrl("/owner/menu"))
			.andExpect(flash().attribute("flashSuccess", "“Mutton Thali” is off your menu. Past orders still show it."));
		assertThat(items.findById(k.thali().getId()).orElseThrow().isArchived()).isTrue();
		assertThat(jdbc.queryForObject("select menu_item_id from order_items where order_id = ?", Long.class, order.getId()))
			.isEqualTo(k.thali().getId());
	}

	@Test
	void aPhotoStoredByAChangeThatRollsBackIsRemovedAgain() throws Exception {
		Kitchen k = kitchen();
		long files = storedDishPhotos();
		doThrow(new IllegalStateException("failure after the store")).when(storage).deleteAfterCommit(any());
		assertThatThrownBy(() -> mvc.perform(dishForm("/owner/menu/items/" + k.thali().getId(), k.main(), "Mutton Thali",
				"250", "false")
			.file(new MockMultipartFile("image", "a.png", "image/png", PNG)).with(k.owner(userDetails))))
			.hasRootCauseMessage("failure after the store");
		assertThat(items.findById(k.thali().getId()).orElseThrow().getImagePath()).isNull();
		assertThat(storedDishPhotos()).isEqualTo(files);
	}

	// ---------------------------------------------------------------- access

	@Test
	void anotherOwnersDishesAreNotFound() throws Exception {
		Kitchen mine = kitchen();
		Kitchen theirs = kitchen();
		long id = theirs.thali().getId();
		RequestPostProcessor me = mine.owner(userDetails);
		mvc.perform(get("/owner/menu/items/{id}", id).with(me)).andExpect(status().isNotFound());
		mvc.perform(dishForm("/owner/menu/items/" + id, mine.main(), "Mine now", "1", "true").with(me)).andExpect(status().isNotFound());
		mvc.perform(post("/owner/menu/items/{id}/availability", id).param("available", "false").with(me).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/owner/menu/items/{id}/delete", id).with(me).with(csrf())).andExpect(status().isNotFound());
		MenuItem untouched = items.findById(id).orElseThrow();
		assertThat(untouched.getName()).isEqualTo("Mutton Thali");
		assertThat(untouched.isAvailable()).isTrue();
		assertThat(untouched.isArchived()).isFalse();
	}

	@Test
	void onlyRestaurantOwnersGetIn() throws Exception {
		Kitchen k = kitchen();
		mvc.perform(get("/owner/menu")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/owner/menu/categories").param("name", "Hacked").with(csrf())).andExpect(redirectedUrl("/login"));
		RequestPostProcessor customer = user(userDetails.loadUserByUsername(orders.customer().getEmail()));
		mvc.perform(get("/owner/menu").with(customer)).andExpect(status().isForbidden());
		mvc.perform(get("/owner/menu/items/{id}", k.thali().getId()).with(customer)).andExpect(status().isForbidden());
		mvc.perform(post("/owner/menu/items/{id}/delete", k.thali().getId()).with(customer).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(post("/owner/menu/items/{id}/delete", k.thali().getId()).with(k.owner(userDetails))).andExpect(status().isForbidden()); // no CSRF token
		assertThat(items.findById(k.thali().getId())).isPresent();
	}

	@Test
	void anOwnerAccountWithoutARestaurantGetsA404() throws Exception {
		User owner = users.save(new User("No Kitchen", slug() + "@owners.zaika.test", "9876543210", "{noop}x",
				Role.RESTAURANT_OWNER));
		RequestPostProcessor me = user(userDetails.loadUserByUsername(owner.getEmail()));
		mvc.perform(get("/owner/menu").with(me)).andExpect(status().isNotFound());
		mvc.perform(get("/owner/menu/items/new").with(me)).andExpect(status().isNotFound());
		mvc.perform(post("/owner/menu/categories").param("name", "Starters").with(me).with(csrf())).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- helpers

	private static MockMultipartHttpServletRequestBuilder dishForm(String url, MenuCategory category, String name,
			String price, String veg) {
		MockMultipartHttpServletRequestBuilder form = multipart(url);
		form.param("categoryId", category.getId().toString()).param("name", name).param("price", price);
		if (veg != null) {
			form.param("veg", veg);
		}
		form.with(csrf());
		return form;
	}

	private long storedDishPhotos() throws java.io.IOException {
		Path dir = storage.root().resolve("dish");
		if (!java.nio.file.Files.isDirectory(dir)) {
			return 0;
		}
		try (var listing = java.nio.file.Files.list(dir)) {
			return listing.count();
		}
	}

	private String html(RequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

}
