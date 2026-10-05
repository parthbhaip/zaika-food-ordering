package com.zaika.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.Restaurant;
import com.zaika.user.Role;
import com.zaika.user.User;

/**
 * The img helper: uploaded image, else a bundled seed photo, else a generated placeholder. Seed photos for these tests
 * live in src/test/resources/static/img/seed (zaika-test-kitchen.jpg, test-misal-pav.jpg).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ImageUrlsTest.ProbeController.class)
@WithMockUser
class ImageUrlsTest {

	final ImageUrls img = new ImageUrls();

	@Autowired
	MockMvc mvc;

	@Test
	void anUploadedImageWinsOverASeedPhoto() {
		Restaurant seeded = restaurant("zaika-test-kitchen", "Zaika Test Kitchen");
		seeded.setCoverPath("cover/0f8c.webp");
		seeded.setLogoPath("logo/77aa.png");

		assertThat(img.cover(seeded)).isEqualTo("/uploads/cover/0f8c.webp");
		assertThat(img.logo(seeded)).isEqualTo("/uploads/logo/77aa.png");
		assertThat(img.dish(item(seeded, "Test Misal Pav", "dish/1b2c.jpg"))).isEqualTo("/uploads/dish/1b2c.jpg");
	}

	@Test
	void aSeedPhotoIsUsedWhenItShipsWithTheApp() {
		Restaurant seeded = restaurant("zaika-test-kitchen", "Zaika Test Kitchen");

		assertThat(img.cover(seeded)).isEqualTo("/img/seed/zaika-test-kitchen.jpg");
		assertThat(img.dish(item(seeded, "Test Misal Pav", null))).as("found by slugified dish name")
			.isEqualTo("/img/seed/test-misal-pav.jpg");
		assertThat(img.dish(item(seeded, "  Test Misál Pav!  ", " "))).as("blank upload path counts as none")
			.isEqualTo("/img/seed/test-misal-pav.jpg");
	}

	@Test
	void otherwiseAGeneratedPlaceholder() {
		Restaurant plain = restaurant("rankala-tandoor", "Rankala Tandoor");

		assertThat(img.cover(plain)).isEqualTo("/placeholder/restaurant/rankala-tandoor.svg");
		assertThat(img.logo(restaurant("zaika-test-kitchen", "Zaika Test Kitchen")))
			.as("no zaika-test-kitchen-logo.jpg seed, so the initials badge")
			.isEqualTo("/placeholder/logo/zaika-test-kitchen.svg");
		assertThat(img.dish(item(plain, "Tambda Rassa Thali", null))).isEqualTo("/placeholder/dish/tambda-rassa-thali.svg");
		assertThat(img.dish(item(plain, "!!!", null))).as("a name with no letters still gets a valid seed")
			.isEqualTo("/placeholder/dish/dish.svg");
	}

	@Test
	void everyUrlTheHelperPicksIsActuallyServed() throws Exception {
		for (String url : new String[] { "/img/seed/zaika-test-kitchen.jpg", "/img/seed/test-misal-pav.jpg",
				img.cover(restaurant("rankala-tandoor", "Rankala Tandoor")),
				img.logo(restaurant("rankala-tandoor", "Rankala Tandoor")),
				img.dish(item(restaurant("rankala-tandoor", "Rankala Tandoor"), "!!!", null)) }) {
			mvc.perform(get(url)).andExpect(status().isOk());
		}
	}

	@Test
	void templatesUseItAsTheImgBean() throws Exception {
		String html = mvc.perform(get("/probe/images")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html)
			.contains("id=\"cover\" src=\"/placeholder/restaurant/rankala-tandoor.svg\"")
			.contains("id=\"logo\" src=\"/placeholder/logo/rankala-tandoor.svg\"")
			.contains("id=\"uploaded\" src=\"/uploads/dish/1b2c.jpg\"")
			.contains("id=\"seeded\" src=\"/img/seed/test-misal-pav.jpg\"");
	}

	static Restaurant restaurant(String slug, String name) {
		return new Restaurant(new User("Ravi Jadhav", "ravi@example.com", "9876543210", "hash", Role.RESTAURANT_OWNER),
				slug, name);
	}

	static MenuItem item(Restaurant restaurant, String name, String imagePath) {
		MenuItem item = new MenuItem(new MenuCategory(restaurant, "Mains", 1), name, new BigDecimal("120.00"), true);
		item.setImagePath(imagePath);
		return item;
	}

	@Controller
	static class ProbeController {

		@GetMapping("/probe/images")
		String images(Model model) {
			Restaurant restaurant = restaurant("rankala-tandoor", "Rankala Tandoor");
			model.addAttribute("restaurant", restaurant);
			model.addAttribute("uploadedItem", item(restaurant, "Chicken Sukka", "dish/1b2c.jpg"));
			model.addAttribute("seededItem", item(restaurant, "Test Misal Pav", null));
			return "probe/images";
		}

	}

}
