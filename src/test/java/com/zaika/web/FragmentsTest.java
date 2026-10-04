package com.zaika.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.catalog.RestaurantCard;

/** Every shared fragment, rendered through a test-only page (templates/probe/fragments.html) in the site layout. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FragmentsTest.ProbeController.class)
@WithMockUser(roles = "CUSTOMER")
class FragmentsTest {

	@Autowired
	MockMvc mvc;

	@Test
	void formFieldShowsLabelHelpAndInlineErrorAndKeepsInput() throws Exception {
		String html = render("/probe/fragments");
		assertThat(html)
			.contains("<div class=\"field has-error\">")
			.contains("<label class=\"field-label\" for=\"email\">Email</label>")
			.containsPattern("<input class=\"input\" type=\"email\" id=\"email\" name=\"email\"\\s+value=\"not-an-email\"\\s+"
					+ "autocomplete=\"email\"\\s+aria-invalid=\"true\"\\s+aria-describedby=\"email-error email-help\">")
			.contains("<p class=\"field-help\" id=\"email-help\">We send order updates here.</p>")
			.containsPattern("<p class=\"field-error\" id=\"email-error\"><svg[^>]*>.*?</svg><span>Enter a valid email address.</span></p>")
			// a valid field: no error state, no dangling aria-describedby
			.containsPattern("<input class=\"input\" type=\"text\" id=\"name\" name=\"name\"\\s+value=\"Aditi Patil\"\\s+autocomplete=\"name\"\\s*>")
			// passwords are never echoed back
			.containsPattern("<input class=\"input\" type=\"password\" id=\"password\" name=\"password\"\\s+value=\"\"\\s+autocomplete=\"current-password\"\\s*>")
			.doesNotContain("hunter2")
			// th:action adds the CSRF token
			.containsPattern("<form id=\"probe-form\" action=\"/probe/fragments\" method=\"post\">\\s*<input type=\"hidden\" name=\"_csrf\"");
	}

	@Test
	void paginationKeepsTheQueryAndMarksTheCurrentPage() throws Exception {
		String pager = section(render("/probe/fragments?q=biryani&page=3&size=10"), "pager");
		assertThat(pager)
			.contains("<nav class=\"pagination\" aria-label=\"Pages\">")
			.contains("rel=\"prev\" href=\"/probe/fragments?q=biryani&amp;size=10&amp;page=2\"")
			.contains("<a class=\"page-link\" href=\"/probe/fragments?q=biryani&amp;size=10\"")
			.containsPattern("href=\"/probe/fragments\\?q=biryani&amp;size=10&amp;page=3\"\\s+aria-current=\"page\">"
					+ "<span class=\"visually-hidden\">Page </span>3</a>")
			.contains("href=\"/probe/fragments?q=biryani&amp;size=10&amp;page=4\"")
			.contains("href=\"/probe/fragments?q=biryani&amp;size=10&amp;page=12\"")
			.contains("<span class=\"page-gap\" aria-hidden=\"true\">…</span>")
			.contains("Previous page", "Next page")
			.doesNotContain("page=5\"");
		assertThat(section(render("/probe/fragments"), "pager"))
			.as("first page: previous is disabled")
			.contains("<span class=\"page-link page-prev is-disabled\" aria-disabled=\"true\">");
		assertThat(section(render("/probe/fragments"), "single-page")).as("one page needs no pager").doesNotContain("<nav");
	}

	@Test
	void displayFragmentsRenderPricesRatingsVegMarksAndEmptyStates() throws Exception {
		String html = render("/probe/fragments");
		assertThat(section(html, "price")).contains("<span class=\"price\">₹120</span>");
		assertThat(section(html, "price-paise")).contains("<span class=\"price\">₹99.50</span>");
		assertThat(section(html, "discounted")).contains("₹249", "<span class=\"visually-hidden\">, was </span>",
				"<s class=\"price-old\">₹299</s>");
		assertThat(section(html, "rating")).contains("class=\"rating-pill\"", "4.3",
				"<span class=\"visually-hidden\"> out of 5, 120 ratings</span>").doesNotContain("New");
		assertThat(section(html, "rating-new")).contains("<span class=\"rating-pill is-new\">New");
		assertThat(section(html, "veg")).contains("<span class=\"veg-mark\"><span class=\"visually-hidden\">Vegetarian</span></span>");
		assertThat(section(html, "non-veg")).contains("<span class=\"veg-mark non-veg\"><span class=\"visually-hidden\">Non-vegetarian</span></span>");
		assertThat(section(html, "empty")).contains("class=\"empty-state\"", "href=\"/img/icons.svg#cart\"",
				"<h2 class=\"empty-state-title\">Your cart is empty</h2>",
				"<a class=\"btn btn-primary\" href=\"/restaurants\">Browse restaurants</a>");
		assertThat(section(html, "empty-no-action")).contains("No matches").doesNotContain("<a ");
	}

	@Test
	void restaurantCardShowsOpenAndClosedRestaurants() throws Exception {
		String html = render("/probe/fragments");
		assertThat(html)
			.contains("<article class=\"restaurant-card\">")
			.contains("<a class=\"restaurant-card-link\" href=\"/r/panchganga-misal-house\">Panchganga Misal House</a>")
			.contains("src=\"/uploads/restaurants/cover-1.webp\"", "loading=\"lazy\"", "width=\"400\" height=\"250\"")
			.contains("<span class=\"restaurant-card-offer\">Free delivery above ₹499</span>")
			.contains("<p class=\"restaurant-card-cuisines\">Maharashtrian, Street food</p>")
			.contains("25-30 min", "₹300 for two", "Pure veg")
			// closed one: greyed out, next opening time, placeholder photo, "New" rating, no offer shown
			.contains("<article class=\"restaurant-card is-closed\">")
			.contains("<span class=\"restaurant-card-closed\">Closed now · Opens at 6:00 PM</span>")
			.contains("src=\"/img/placeholder-food.svg\"")
			.doesNotContain("20% off");
	}

	@Test
	void flashAttributesBecomeToastsInTheLayout() throws Exception {
		String html = mvc.perform(get("/probe/fragments").flashAttr("flashSuccess", "Address saved")
				.flashAttr("flashError", "Couldn't apply ZAIKA50"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(section(html, "toasts"))
			.contains("<div id=\"toasts\" class=\"toast-stack\" aria-live=\"polite\">")
			.containsPattern("<div class=\"toast toast-success\">\\s*<svg[^>]*>.*?#check.*?</svg>\\s*"
					+ "<div class=\"toast-body\"><span class=\"toast-title\">Address saved</span></div>\\s*"
					+ "<button type=\"button\" class=\"toast-close\" data-toast-close aria-label=\"Dismiss\"><svg[^>]*>.*?#x.*?</svg></button>")
			.containsPattern("<div class=\"toast toast-error\"\\s+role=\"alert\">")
			.contains("Couldn&#39;t apply ZAIKA50")
			.as("every toast, including the sticky error one, can be dismissed")
			.containsPattern("(?s)role=\"alert\">.*?Couldn&#39;t apply ZAIKA50.*?class=\"toast-close\"");
		assertThat(section(render("/probe/fragments"), "toasts")).as("no flash, no toast").doesNotContain("class=\"toast ");
	}

	private String render(String url) throws Exception {
		return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/** The element with this id, up to the next element with an id (good enough for these flat probes). */
	private static String section(String html, String id) {
		int start = html.indexOf("id=\"" + id + "\"");
		assertThat(start).as("#%s", id).isNotNegative();
		int next = html.indexOf(" id=\"", start + 4);
		return html.substring(html.lastIndexOf('<', start), next < 0 ? html.length() : next);
	}

	@Controller
	static class ProbeController {

		@GetMapping("/probe/fragments")
		String fragments(Pageable pageable, Model model) {
			ProbeForm form = new ProbeForm("not-an-email", "Aditi Patil", "hunter2");
			BindingResult errors = new BeanPropertyBindingResult(form, "form");
			errors.rejectValue("email", "Email", "Enter a valid email address.");
			model.addAttribute("form", form);
			model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", errors);
			model.addAttribute("page", new PageImpl<>(List.of(), pageable, 120));
			model.addAttribute("singlePage", new PageImpl<>(List.of("only")));
			model.addAttribute("whole", new BigDecimal("120.00"));
			model.addAttribute("paise", new BigDecimal("99.5"));
			model.addAttribute("discounted", new BigDecimal("249"));
			model.addAttribute("was", new BigDecimal("299"));
			model.addAttribute("rating", new BigDecimal("4.25"));
			model.addAttribute("restaurants", List.of(
					new RestaurantCard("panchganga-misal-house", "Panchganga Misal House", "/uploads/restaurants/cover-1.webp",
							List.of("Maharashtrian", "Street food"), new BigDecimal("4.5"), 1240, 25, new BigDecimal("300"),
							true, "Free delivery above ₹499", true, null),
					new RestaurantCard("rankala-night-kitchen", "Rankala Night Kitchen", null, List.of("North Indian"),
							BigDecimal.ZERO, 0, 40, new BigDecimal("400"), false, "20% off up to ₹100", false,
							LocalTime.of(18, 0))));
			return "probe/fragments";
		}

	}

	public static class ProbeForm {

		private final String email;

		private final String name;

		private final String password;

		ProbeForm(String email, String name, String password) {
			this.email = email;
			this.name = name;
			this.password = password;
		}

		public String getEmail() {
			return email;
		}

		public String getName() {
			return name;
		}

		public String getPassword() {
			return password;
		}

	}

}
