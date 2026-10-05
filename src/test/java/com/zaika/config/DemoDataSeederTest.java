package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.MenuCategoryRepository;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.user.Address;
import com.zaika.user.AddressRepository;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * The demo seeder on its own in-memory database with {@code app.seed-demo=true}: what it loads at startup, and that
 * running it again changes nothing. No ADMIN_EMAIL is set here, so the seeder also creates the demo admin.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:demoseed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"app.seed-demo=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DemoDataSeederTest {

	static final List<String> TABLES = List.of("users", "addresses", "cuisines", "restaurants", "restaurant_cuisines",
			"menu_categories", "menu_items");

	/** Typical demo hours: a college demo or a recruiter's visit happens somewhere between 10 AM and 9 PM. */
	static final List<LocalTime> DEMO_TIMES = IntStream.rangeClosed(10, 21).mapToObj(h -> LocalTime.of(h, 0)).toList();

	@Autowired
	DemoDataSeeder seeder;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	AddressRepository addresses;

	@Autowired
	CuisineRepository cuisines;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	MockMvc mvc;

	@Test
	void runningItAgainChangesNothing() throws Exception {
		Map<String, List<Map<String, Object>>> before = snapshot();
		assertThat(before.get("restaurants")).hasSize(13);

		seeder.run(null);
		seeder.run(null);

		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	@Transactional
	void tenCuisinesAllInUse() {
		List<Cuisine> all = cuisines.findAllByOrderByNameAsc();
		assertThat(all).extracting(Cuisine::getName)
			.containsExactlyInAnyOrder("Kolhapuri", "Malvani", "Maharashtrian", "South Indian", "North Indian",
					"Biryani", "Chinese", "Pizza", "Street Food", "Desserts");
		Set<String> used = approved().stream()
			.flatMap(r -> r.getCuisines().stream())
			.map(Cuisine::getName)
			.collect(Collectors.toSet());
		assertThat(used).hasSize(10);
	}

	@Test
	@Transactional
	void twelveApprovedRestaurantsSpreadAcrossKolhapur() {
		List<Restaurant> approved = approved();
		assertThat(approved).hasSize(12);
		assertThat(approved).allSatisfy(r -> {
			assertThat(r.getCity()).isEqualTo("Kolhapur");
			assertThat(r.getPincode()).startsWith("416");
			assertThat(r.getTagline()).isNotBlank();
			assertThat(r.getDescription()).isNotBlank();
			assertThat(r.getCuisines()).isNotEmpty();
			assertThat(r.getRatingCount()).isPositive();
			assertThat(r.getRatingAverage()).isBetween(new BigDecimal("3.50"), new BigDecimal("5.00"));
			assertThat(r.isAcceptingOrders()).isTrue();
		});
		assertThat(approved).extracting(Restaurant::getArea).doesNotHaveDuplicates();
		assertThat(approved).extracting(Restaurant::getName).doesNotHaveDuplicates();
		// local specialities
		assertThat(items.findAll()).extracting(MenuItem::getName)
			.contains("Tambda Rassa", "Pandhra Rassa", "Kolhapuri Misal Pav", "Kombdi Vade", "Solkadhi");
		// a mix of pure veg and non-veg
		assertThat(approved).filteredOn(Restaurant::isPureVeg).hasSizeBetween(3, 9);
		// ratings vary, so "Top rated" and "4.0+" have something to sort and filter
		assertThat(approved).filteredOn(r -> r.getRatingAverage().compareTo(new BigDecimal("4.0")) < 0).isNotEmpty();
	}

	@Test
	@Transactional
	void hoursVaryWithOneClosedAtDemoTimesAndOneOpenOvernight() {
		List<Restaurant> approved = approved();

		assertThat(approved).filteredOn(r -> DEMO_TIMES.stream().noneMatch(r::isOpenAt))
			.extracting(Restaurant::getSlug)
			.containsExactly("jhanjhanit-misal-ghar");
		assertThat(approved).filteredOn(r -> r.getClosesAt().isBefore(r.getOpensAt()))
			.singleElement()
			.satisfies(r -> {
				assertThat(r.getSlug()).isEqualTo("noor-biryani-kebabs");
				assertThat(r.isOpenAt(LocalTime.of(1, 30))).isTrue();
				assertThat(r.isOpenAt(LocalTime.of(13, 0))).isTrue();
			});
		// the rest are open for lunch and dinner, so the demo has plenty to order from
		for (LocalTime time : List.of(LocalTime.of(13, 0), LocalTime.of(20, 0))) {
			assertThat(approved).filteredOn(r -> r.isOpenAt(time)).hasSize(11);
		}
		assertThat(approved.stream().map(Restaurant::getOpensAt).collect(Collectors.toSet())).as("varied opening times")
			.hasSizeGreaterThan(4);
	}

	@Test
	@Transactional
	void everyApprovedRestaurantHasAFullRealisticMenu() {
		for (Restaurant r : approved()) {
			assertThat(categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId())).as(r.getSlug())
				.hasSizeBetween(3, 5);
			List<MenuItem> menu = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(r.getId());
			assertThat(menu).as(r.getSlug()).hasSizeBetween(8, 15);
			assertThat(menu).as(r.getSlug()).allSatisfy(item -> {
				assertThat(item.getPrice()).isBetween(new BigDecimal("20.00"), new BigDecimal("600.00"));
				assertThat(item.getPrice().scale()).isEqualTo(2);
				assertThat(item.getDescription()).isNotBlank().endsWith(".");
				assertThat(item.getCategory().getRestaurant()).isEqualTo(r);
			});
			assertThat(menu).as(r.getSlug() + " bestsellers").filteredOn(MenuItem::isBestseller).isNotEmpty();
			assertThat(menu).as(r.getSlug() + " sold out").filteredOn(Predicate.not(MenuItem::isAvailable)).hasSize(1);
			assertThat(menu).extracting(MenuItem::getName).as(r.getSlug()).doesNotHaveDuplicates();
			if (r.isPureVeg()) {
				assertThat(menu).as(r.getSlug() + " is pure veg").allMatch(MenuItem::isVeg);
			}
			else {
				assertThat(menu).as(r.getSlug() + " serves non-veg").anyMatch(item -> !item.isVeg());
			}
		}
		assertThat(items.findAll()).extracting(MenuItem::getSpiceLevel).contains(0, 1, 2, 3);
	}

	@Test
	@Transactional
	void onePendingRestaurantWaitsForApproval() {
		List<Restaurant> pending = restaurants.findAll()
			.stream()
			.filter(r -> r.getStatus() == RestaurantStatus.PENDING)
			.toList();
		assertThat(pending).singleElement().satisfies(r -> {
			assertThat(r.getSlug()).isEqualTo("brew-lab-cafe");
			assertThat(r.getRatingCount()).isZero();
		});
	}

	@Test
	@Transactional
	void demoAccountsExistWithTheDemoPassword() {
		Map<String, Role> expected = Map.of("admin@zaika.test", Role.ADMIN, "owner1@zaika.test", Role.RESTAURANT_OWNER,
				"owner2@zaika.test", Role.RESTAURANT_OWNER, "rider1@zaika.test", Role.DELIVERY_PARTNER,
				"rider2@zaika.test", Role.DELIVERY_PARTNER, "customer@zaika.test", Role.CUSTOMER);
		expected.forEach((email, role) -> {
			User user = users.findByEmail(email).orElseThrow();
			assertThat(user.getRole()).as(email).isEqualTo(role);
			assertThat(user.isEnabled()).isTrue();
			assertThat(passwordEncoder.matches("Demo@1234", user.getPasswordHash())).as(email).isTrue();
		});

		Restaurant first = restaurants.findByOwnerId(users.findByEmail("owner1@zaika.test").orElseThrow().getId())
			.orElseThrow();
		Restaurant second = restaurants.findByOwnerId(users.findByEmail("owner2@zaika.test").orElseThrow().getId())
			.orElseThrow();
		assertThat(List.of(first, second)).extracting(Restaurant::getStatus).containsOnly(RestaurantStatus.APPROVED);
		assertThat(first.isPureVeg()).isFalse();
		assertThat(second.isPureVeg()).isTrue();

		// every restaurant has its own owner account
		assertThat(restaurants.findAll()).extracting(r -> r.getOwner().getId()).doesNotHaveDuplicates();

		// the customer can check out straight away
		User customer = users.findByEmail("customer@zaika.test").orElseThrow();
		assertThat(addresses.findByUserIdOrderByDefaultAddressDescIdDesc(customer.getId())).singleElement()
			.satisfies(a -> {
				assertThat(a.isDefaultAddress()).isTrue();
				assertThat(a.getCity()).isEqualTo("Kolhapur");
			})
			.extracting(Address::getPincode)
			.isEqualTo("416008");
	}

	@Test
	void demoOwnerCanSignIn() throws Exception {
		mvc.perform(post("/login").param("email", "owner1@zaika.test").param("password", "Demo@1234").with(csrf()))
			.andExpect(redirectedUrl("/owner"));
	}

	@Test
	@Transactional
	void anAccountAlreadyUsingADemoEmailIsKeptAsIs() {
		// a customer took a demo owner's email before seeding: the account stays a customer, the restaurant is skipped
		User owner3 = users.findByEmail("owner3@zaika.test").orElseThrow();
		Restaurant misal = restaurants.findByOwnerId(owner3.getId()).orElseThrow();
		items.deleteAll(items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(misal.getId()));
		categories.deleteAll(categories.findByRestaurantIdOrderBySortOrderAscIdAsc(misal.getId()));
		restaurants.delete(misal);
		users.delete(owner3);
		users.flush();
		User squatter = users.save(new User("Someone Else", "owner3@zaika.test", "9000000003", "$2a$10$hash",
				Role.CUSTOMER));

		seeder.run(null);

		assertThat(users.findByEmail("owner3@zaika.test")).contains(squatter);
		assertThat(squatter.getRole()).isEqualTo(Role.CUSTOMER);
		assertThat(squatter.getPasswordHash()).isEqualTo("$2a$10$hash");
		assertThat(restaurants.findBySlug("jhanjhanit-misal-ghar")).isEmpty();
		assertThat(restaurants.count()).isEqualTo(12);
	}

	private List<Restaurant> approved() {
		return restaurants.findAll().stream().filter(r -> r.getStatus() == RestaurantStatus.APPROVED).toList();
	}

	/** Every row of every seeded table, including version and updated_at, so updates show up as well as inserts. */
	private Map<String, List<Map<String, Object>>> snapshot() {
		return TABLES.stream()
			.collect(Collectors.toMap(table -> table,
					table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2")));
	}

}
