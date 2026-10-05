package com.zaika.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.zaika.security.SecurityConfig;

/** The site layout (header, footer, htmx wiring), the static pages and the security headers. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LayoutTest {

	static final Map<String, String> STATIC_PAGES = Map.of(
			"/about", "About us",
			"/contact", "Help and support",
			"/terms", "Terms of use",
			"/privacy", "Privacy policy",
			"/refund-policy", "Cancellations and refunds");

	@Autowired
	MockMvc mvc;

	@Test
	void everyStaticPageRendersInTheLayoutForAnonymousVisitors() throws Exception {
		for (Map.Entry<String, String> page : STATIC_PAGES.entrySet()) {
			String html = mvc.perform(get(page.getKey()))
				.andExpect(status().isOk())
				.andExpect(view().name("pages" + page.getKey()))
				.andReturn().getResponse().getContentAsString();
			assertThat(html).as(page.getKey())
				.startsWith("<!DOCTYPE html>")
				.contains("<html lang=\"en\">", "<title>" + page.getValue() + " | Zaika</title>")
				.contains("<a class=\"skip-link\" href=\"#main\">Skip to content</a>", "<main id=\"main\"")
				.contains("<header class=\"site-header\">", "<footer class=\"site-footer\">")
				.contains("<h1>")
				// CSP-safe: every script is a file, no inline handlers
				.doesNotContainPattern("<script(?![^>]*\\ssrc=)")
				.doesNotContainPattern("\\son[a-z]+=")
				.doesNotContain("lorem", "Lorem", "coming soon", "Coming soon");
		}
	}

	@Test
	void layoutLoadsHtmxFromWebJarsWithCsrfMetaTags() throws Exception {
		String html = page("/about");
		assertThat(html)
			// webjars-locator-lite adds the version to the URL
			.containsPattern("<script defer src=\"/webjars/htmx.org/2\\.[0-9.]+/dist/htmx\\.min\\.js\"></script>")
			.contains("<script defer src=\"/js/htmx-config.js\"></script>")
			.containsPattern("<meta name=\"_csrf\" content=\"[A-Za-z0-9_=-]{20,}\">")
			.contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">")
			.contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/img/favicon.svg\">")
			.contains("href=\"/css/tokens.css\"", "href=\"/css/app.css\"");
		assertThat(html.indexOf("htmx.min.js")).as("htmx-config.js runs after htmx").isLessThan(html.indexOf("htmx-config.js"));

		for (String asset : new String[] { "/webjars/htmx.org/dist/htmx.min.js", "/js/htmx-config.js", "/js/menus.js" }) {
			mvc.perform(get(asset)).andExpect(status().isOk());
		}
		String config = read("static/js/htmx-config.js");
		assertThat(config).contains("htmx.config.includeIndicatorStyles = false;", "htmx.config.allowEval = false;",
				"'htmx:configRequest'", "meta[name=\"_csrf\"]", "meta[name=\"_csrf_header\"]", "event.detail.headers[");
		assertThat(read("static/js/menus.js")).as("toast close buttons are wired")
			.contains("closest('[data-toast-close]')", "closest('.toast').remove()");
		for (String js : new String[] { "static/js/htmx-config.js", "static/js/menus.js" }) {
			assertThat(read(js).lines().count()).as(js + " stays small").isLessThan(60);
		}
	}

	@Test
	void headerHasLogoCitySearchNavAndCartForAnonymousVisitors() throws Exception {
		String html = page("/about?q=misal");
		assertThat(html)
			.contains("<a class=\"site-logo\" href=\"/\"><img src=\"/img/logo.svg\" alt=\"Zaika home\" width=\"112\" height=\"35\"></a>")
			.contains("Kolhapur</p>")
			.contains("<form class=\"header-search\" role=\"search\" action=\"/search\" method=\"get\">")
			.contains("<label class=\"visually-hidden\" for=\"header-search-q\">Search restaurants and dishes</label>")
			.containsPattern("<input class=\"input\" id=\"header-search-q\" name=\"q\" type=\"search\"[^>]*value=\"misal\">")
			.contains("<nav class=\"header-nav\" aria-label=\"Main\">", "href=\"/restaurants\">Restaurants</a>")
			// cart with a live count, hidden visually while empty
			.contains("<a class=\"header-cart\" href=\"/cart\">")
			.contains("<span id=\"cart-count\" aria-live=\"polite\"><span class=\"cart-count is-empty\"><span>0</span><span class=\"visually-hidden\"> items</span></span></span>")
			// sign in / sign up, no account menu
			.contains("<a class=\"btn btn-ghost btn-sm\" href=\"/login\">Sign in</a>", "<a class=\"btn btn-primary btn-sm\" href=\"/register\">Sign up</a>")
			.doesNotContain("account-menu", "action=\"/logout\"")
			// phone drawer: a native <details>, labelled toggle, its own nav
			.contains("<details class=\"nav-drawer\" data-menu>", "<span class=\"visually-hidden\">Menu</span></summary>",
					"<nav class=\"drawer-nav\" aria-label=\"Menu\">", "href=\"/partner-with-us\"")
			.contains("<script defer src=\"/js/menus.js\"></script>");
	}

	@Test
	@WithMockUser(username = "customer@zaika.test", roles = "CUSTOMER")
	void customerSeesCartOrdersAndLogout() throws Exception {
		String html = page("/about");
		assertThat(html)
			.contains("<details class=\"account-menu\" data-menu>", "Signed in as <strong>customer@zaika.test</strong>")
			.contains("href=\"/orders\"", "href=\"/account/favourites\"", "href=\"/account\"", "<a class=\"header-cart\" href=\"/cart\">")
			.containsPattern("<form class=\"account-links-logout\" action=\"/logout\" method=\"post\">\\s*<input type=\"hidden\" name=\"_csrf\"")
			.doesNotContain("href=\"/login\"", "href=\"/register\"", "href=\"/owner\"", "href=\"/partner\"", "href=\"/admin\"");
	}

	@Test
	@WithMockUser(roles = "RESTAURANT_OWNER")
	void ownerSeesTheRestaurantPanelLinksAndNoCart() throws Exception {
		assertThat(page("/about"))
			.contains("href=\"/owner\"", "href=\"/owner/orders\"", "href=\"/owner/menu\"", "action=\"/logout\"")
			.doesNotContain("href=\"/cart\"", "href=\"/orders\"", "href=\"/partner\"", "href=\"/admin\"");
	}

	@Test
	@WithMockUser(roles = "DELIVERY_PARTNER")
	void riderSeesTheRiderPanelLinks() throws Exception {
		assertThat(page("/about"))
			.contains("href=\"/partner\"", "href=\"/partner/earnings\"", "action=\"/logout\"")
			.doesNotContain("href=\"/cart\"", "href=\"/owner\"", "href=\"/admin\"");
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminSeesTheAdminLinks() throws Exception {
		assertThat(page("/about"))
			.contains("href=\"/admin\"", "href=\"/admin/restaurants\"", "href=\"/admin/orders\"", "action=\"/logout\"")
			.doesNotContain("href=\"/cart\"", "href=\"/owner\"", "href=\"/partner\"");
	}

	@Test
	void footerLinksAboutPartnersAndLegalWithTheClockYear() throws Exception {
		String footer = page("/terms");
		footer = footer.substring(footer.indexOf("<footer class=\"site-footer\">"));
		assertThat(footer).contains("href=\"/about\"", "href=\"/contact\"", "href=\"/partner-with-us/restaurant\"",
				"href=\"/partner-with-us/rider\"", "href=\"/terms\"", "href=\"/privacy\"", "href=\"/refund-policy\"",
				"<span>2026</span> Zaika. Made with care in Kolhapur.");
	}

	@Test
	void staticPagesHaveRealContent() throws Exception {
		assertThat(page("/contact")).contains("href=\"mailto:support@zaika.test\"", "href=\"tel:+919876543210\"",
				"<details class=\"faq-item\">", "Can I cancel my order?", "Shahupuri, Kolhapur 416001");
		assertThat(page("/refund-policy")).contains("<table class=\"table\">", "before the restaurant accepts",
				"5 to 7 working days").containsPattern("href=\"/refund-policy\"\\s+aria-current=\"page\"");
		assertThat(page("/privacy")).contains("Digital Personal Data Protection Act, 2023", "Grievance officer");
		assertThat(page("/terms")).contains("₹5 platform fee", "courts at Kolhapur");
		assertThat(page("/about")).contains("How Zaika works", "href=\"/restaurants\">Order now</a>");
	}

	@Test
	void securityHeadersArePresent() throws Exception {
		mvc.perform(get("/about"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
		assertThat(SecurityConfig.CONTENT_SECURITY_POLICY).isEqualTo("default-src 'self'; script-src 'self'; "
				+ "img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
		// static assets carry the same headers
		mvc.perform(get("/css/app.css"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"));
	}

	@Test
	void pagesOutsideThePublicListStillRequireSignIn() throws Exception {
		mvc.perform(get("/account")).andExpect(status().is3xxRedirection());
	}

	private String page(String url) throws Exception {
		return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static String read(String path) throws Exception {
		return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
	}

}
