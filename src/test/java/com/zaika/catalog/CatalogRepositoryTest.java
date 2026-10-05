package com.zaika.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.config.TimeConfig;
import com.zaika.support.TestClockConfig;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/** The catalog tables (Flyway V4), their mappings and repository queries on H2 in MySQL mode. */
@DataJpaTest
@ActiveProfiles("test")
@Import({ TimeConfig.class, TestClockConfig.class })
class CatalogRepositoryTest {

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	CuisineRepository cuisines;

	@Autowired
	MenuCategoryRepository categories;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	Cuisine maharashtrian;

	Cuisine streetFood;

	@BeforeEach
	void cuisines() {
		streetFood = cuisines.save(new Cuisine("Street Food"));
		maharashtrian = cuisines.save(new Cuisine("Maharashtrian"));
	}

	private User owner(String email) {
		return users.save(new User("Owner " + email, email, "9876543210", "hash", Role.RESTAURANT_OWNER));
	}

	private Restaurant restaurant(String slug, User owner) {
		Restaurant r = new Restaurant(owner, slug, "Panchganga Misal House");
		r.setPhone("9823012345");
		r.setAddress("Shop 4, Mahadwar Road");
		r.setArea("Mahadwar Road");
		r.setCity("Kolhapur");
		r.setPincode("416012");
		r.setCostForTwo(new BigDecimal("250"));
		r.setHours(LocalTime.of(7, 0), LocalTime.of(22, 30));
		return r;
	}

	@Test
	void aRestaurantRoundTripsWithEveryField() {
		Restaurant r = restaurant("panchganga-misal-house", owner("owner@example.com"));
		r.setTagline("Fiery Kolhapuri misal since 1978");
		r.setDescription("Tarri-loaded misal, crisp farsan and soft pav.");
		r.setCuisines(List.of(streetFood, maharashtrian));
		r.setLogoPath("restaurants/logo-1.webp");
		r.setCoverPath("restaurants/cover-1.webp");
		r.setMinimumOrder(new BigDecimal("99"));
		r.setDeliveryFee(new BigDecimal("25"));
		r.setAvgPrepMinutes(15);
		r.setAcceptingOrders(false);
		r.setPureVeg(true);
		r.changeStatus(RestaurantStatus.SUSPENDED, "Hygiene inspection pending");
		r.setRating(new BigDecimal("4.46"), 812);
		Long id = restaurants.saveAndFlush(r).getId();
		em.clear();

		Restaurant loaded = restaurants.findById(id).orElseThrow();
		assertThat(loaded.getSlug()).isEqualTo("panchganga-misal-house");
		assertThat(loaded.getName()).isEqualTo("Panchganga Misal House");
		assertThat(loaded.getTagline()).isEqualTo("Fiery Kolhapuri misal since 1978");
		assertThat(loaded.getDescription()).startsWith("Tarri-loaded");
		assertThat(loaded.getOwner().getEmail()).isEqualTo("owner@example.com");
		assertThat(loaded.getCuisines()).extracting(Cuisine::getName).containsExactly("Maharashtrian", "Street Food");
		assertThat(loaded.getPhone()).isEqualTo("9823012345");
		assertThat(loaded.getAddress()).isEqualTo("Shop 4, Mahadwar Road");
		assertThat(loaded.getArea()).isEqualTo("Mahadwar Road");
		assertThat(loaded.getCity()).isEqualTo("Kolhapur");
		assertThat(loaded.getPincode()).isEqualTo("416012");
		assertThat(loaded.getLogoPath()).isEqualTo("restaurants/logo-1.webp");
		assertThat(loaded.getCoverPath()).isEqualTo("restaurants/cover-1.webp");
		assertThat(loaded.getCostForTwo()).isEqualTo(new BigDecimal("250.00"));
		assertThat(loaded.getMinimumOrder()).isEqualTo(new BigDecimal("99.00"));
		assertThat(loaded.getDeliveryFee()).isEqualTo(new BigDecimal("25.00"));
		assertThat(loaded.getAvgPrepMinutes()).isEqualTo(15);
		assertThat(loaded.getOpensAt()).isEqualTo(LocalTime.of(7, 0));
		assertThat(loaded.getClosesAt()).isEqualTo(LocalTime.of(22, 30));
		assertThat(loaded.isAcceptingOrders()).isFalse();
		assertThat(loaded.isPureVeg()).isTrue();
		assertThat(loaded.getStatus()).isEqualTo(RestaurantStatus.SUSPENDED);
		assertThat(loaded.getStatusReason()).isEqualTo("Hygiene inspection pending");
		assertThat(loaded.getRatingAverage()).isEqualTo(new BigDecimal("4.46"));
		assertThat(loaded.getRatingCount()).isEqualTo(812);
		assertThat(jdbc.queryForObject("SELECT status FROM restaurants WHERE id = ?", String.class, id))
			.isEqualTo("SUSPENDED");
	}

	@Test
	void findBySlugLoadsTheCuisinesInTheSameQueryAndFindByOwnerFindsTheirRestaurant() {
		User owner = owner("owner@example.com");
		Restaurant r = restaurant("panchganga-misal-house", owner);
		r.setCuisines(List.of(maharashtrian, streetFood));
		restaurants.saveAndFlush(r);
		em.clear();

		Restaurant bySlug = restaurants.findBySlug("panchganga-misal-house").orElseThrow();
		assertThat(Hibernate.isInitialized(bySlug.getCuisines())).isTrue();
		assertThat(bySlug.getCuisines()).hasSize(2);
		assertThat(restaurants.findBySlug("no-such-place")).isEmpty();
		assertThat(restaurants.findByOwnerId(owner.getId())).get().extracting(Restaurant::getSlug)
			.isEqualTo("panchganga-misal-house");
		assertThat(restaurants.findByOwnerId(-1L)).isEmpty();
	}

	@Test
	void slugsAreUniqueAndAnOwnerHasOnlyOneRestaurant() {
		User owner = owner("one@example.com");
		restaurants.saveAndFlush(restaurant("misal-house", owner));

		assertThatThrownBy(() -> restaurants.saveAndFlush(restaurant("misal-house", owner("two@example.com"))))
			.isInstanceOf(DataIntegrityViolationException.class);
		em.clear();
		assertThatThrownBy(() -> restaurants.saveAndFlush(restaurant("another-house", users.findById(owner.getId())
			.orElseThrow()))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void cuisineNamesAndSlugsAreUniqueAndListedByName() {
		assertThat(cuisines.findAllByOrderByNameAsc()).extracting(Cuisine::getName)
			.containsExactly("Maharashtrian", "Street Food");
		assertThat(cuisines.findBySlug("street-food")).get().extracting(Cuisine::getName).isEqualTo("Street Food");

		assertThatThrownBy(() -> cuisines.saveAndFlush(new Cuisine("Street  food")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidRestaurantDetailsAreRejected() {
		User owner = owner("owner@example.com");
		Restaurant badSlug = restaurant("Misal House", owner);
		assertThatThrownBy(() -> restaurants.saveAndFlush(badSlug)).isInstanceOf(ConstraintViolationException.class);
		em.clear();

		Restaurant noHours = new Restaurant(owner, "misal-house", "Misal House");
		noHours.setPhone("9823012345");
		noHours.setAddress("Shop 4");
		noHours.setArea("Shahupuri");
		noHours.setCity("Kolhapur");
		noHours.setPincode("416001");
		noHours.setCostForTwo(new BigDecimal("300"));
		assertThatThrownBy(() -> restaurants.saveAndFlush(noHours)).isInstanceOf(ConstraintViolationException.class);
		em.clear();

		Restaurant badPin = restaurant("misal-house", owner);
		badPin.setPincode("016001");
		assertThatThrownBy(() -> restaurants.saveAndFlush(badPin)).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	void theDatabaseRejectsUnknownStatusesAndNegativeMoney() {
		Long id = restaurants.saveAndFlush(restaurant("misal-house", owner("owner@example.com"))).getId();

		assertThatThrownBy(() -> jdbc.update("UPDATE restaurants SET status = 'DELETED' WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE restaurants SET delivery_fee = -1 WHERE id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void menuItemsBelongToTheirCategorysRestaurantAndArchivedOnesAreHidden() {
		Restaurant r = restaurants.save(restaurant("misal-house", owner("owner@example.com")));
		MenuCategory sides = categories.save(new MenuCategory(r, "Sides", 2));
		MenuCategory misal = categories.save(new MenuCategory(r, "Misal", 1));

		MenuItem special = new MenuItem(misal, "Special Kolhapuri Misal", new BigDecimal("120"), true);
		special.setDescription("Extra-fiery tarri, double farsan, two pav.");
		special.setBestseller(true);
		special.setSpiceLevel(3);
		special.setSortOrder(1);
		special.setImagePath("menu/special-misal.webp");
		MenuItem plain = new MenuItem(misal, "Misal Pav", new BigDecimal("80"), true);
		plain.setAvailable(false); // sold out today
		plain.setSortOrder(2);
		MenuItem old = new MenuItem(sides, "Old Item", new BigDecimal("40"), true);
		old.setArchived(true);
		MenuItem chaas = new MenuItem(sides, "Masala Chaas", new BigDecimal("35"), true);
		items.saveAll(List.of(special, plain, old, chaas));
		items.flush();
		em.clear();

		assertThat(categories.findByRestaurantIdOrderBySortOrderAscIdAsc(r.getId())).extracting(MenuCategory::getName)
			.containsExactly("Misal", "Sides");
		List<MenuItem> visible = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(r.getId());
		assertThat(visible).extracting(MenuItem::getName)
			.containsExactly("Masala Chaas", "Special Kolhapuri Misal", "Misal Pav");
		MenuItem loaded = visible.get(1);
		assertThat(loaded.getPrice()).isEqualTo(new BigDecimal("120.00"));
		assertThat(loaded.isVeg()).isTrue();
		assertThat(loaded.isBestseller()).isTrue();
		assertThat(loaded.getSpiceLevel()).isEqualTo(3);
		assertThat(loaded.getDescription()).startsWith("Extra-fiery");
		assertThat(loaded.getImagePath()).isEqualTo("menu/special-misal.webp");
		assertThat(visible.get(2).isAvailable()).isFalse();
		assertThat(jdbc.queryForObject("SELECT restaurant_id FROM menu_items WHERE id = ?", Long.class, loaded.getId()))
			.isEqualTo(r.getId());
	}

	@Test
	void spiceLevelIsZeroToThreeAndPriceMustBePositive() {
		Restaurant r = restaurants.save(restaurant("misal-house", owner("owner@example.com")));
		MenuCategory misal = categories.save(new MenuCategory(r, "Misal", 1));

		MenuItem tooHot = new MenuItem(misal, "Volcano Misal", new BigDecimal("150"), true);
		tooHot.setSpiceLevel(4);
		assertThatThrownBy(() -> items.saveAndFlush(tooHot)).isInstanceOf(ConstraintViolationException.class);
		em.clear();
		MenuItem free = new MenuItem(categories.findById(misal.getId()).orElseThrow(), "Free Misal", BigDecimal.ZERO, true);
		assertThatThrownBy(() -> items.saveAndFlush(free)).isInstanceOf(ConstraintViolationException.class);
		em.clear();

		MenuItem ok = items.saveAndFlush(new MenuItem(categories.findById(misal.getId()).orElseThrow(), "Misal",
				new BigDecimal("80"), true));
		assertThatThrownBy(() -> jdbc.update("UPDATE menu_items SET spice_level = 4 WHERE id = ?", ok.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE menu_items SET price = 0 WHERE id = ?", ok.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void listFiltersAndForeignKeysAreIndexed() {
		assertThat(leadingIndexColumns("restaurants")).contains("status", "city", "slug", "owner_id");
		assertThat(leadingIndexColumns("restaurant_cuisines")).contains("restaurant_id", "cuisine_id");
		assertThat(leadingIndexColumns("menu_categories")).contains("restaurant_id");
		assertThat(leadingIndexColumns("menu_items")).contains("restaurant_id", "category_id");
		assertThat(leadingIndexColumns("cuisines")).contains("slug", "name");
	}

	@Test
	void listingFiltersAndSortsAreIndexedBehindTheStatus() {
		List<String> indexes = jdbc.queryForList("""
				SELECT LISTAGG(LOWER(column_name), ',') WITHIN GROUP (ORDER BY ordinal_position)
				FROM information_schema.index_columns WHERE LOWER(table_name) = 'restaurants'
				GROUP BY index_name""", String.class);
		assertThat(indexes).contains("status,rating_average", "status,avg_prep_minutes", "status,cost_for_two",
				"status,pure_veg", "status,accepting_orders,opens_at,closes_at");
	}

	/** Columns that are the first column of some index on {@code table}, i.e. lookups by them use an index. */
	private List<String> leadingIndexColumns(String table) {
		return jdbc.queryForList("""
				SELECT LOWER(column_name) FROM information_schema.index_columns
				WHERE LOWER(table_name) = ? AND ordinal_position = 1""", String.class, table);
	}

}
