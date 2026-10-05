package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.zaika.catalog.RestaurantRepository;
import com.zaika.notification.LoggingMailService;
import com.zaika.notification.MailService;
import com.zaika.security.SecurityConfig;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * The dev profile boots with its own settings. The datasource is swapped for an in-memory one so the test
 * never writes ./data; the real file URL is checked straight from application-dev.properties.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:devprofile;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class DevProfileTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	Environment env;

	@Autowired
	AppProperties app;

	@Autowired
	MailService mail;

	@Autowired
	ApplicationContext context;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	RestaurantRepository restaurants;

	@Test
	void devProfileSeedsDemoDataAndOnlyLogsMail() {
		assertThat(env.getActiveProfiles()).containsExactly("dev");
		assertThat(app.seedDemo()).isTrue();
		assertThat(mail).isInstanceOf(LoggingMailService.class);
		// DemoDataSeeder ran on startup: 12 approved restaurants and one pending, owner1's among them
		assertThat(restaurants.count()).isEqualTo(13);
		assertThat(restaurants.findByOwnerId(users.findByEmail("owner1@zaika.test").orElseThrow().getId())).isPresent();
	}

	@Test
	void devProfileCreatesTheDemoAdminOnStartup() throws Exception {
		Properties dev = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-dev.properties"));
		PropertySourcesPropertyResolver noVariables = new PropertySourcesPropertyResolver(new MutablePropertySources());
		assertThat(noVariables.resolvePlaceholders(dev.getProperty("app.admin.email"))).isEqualTo("admin@zaika.test");
		assertThat(noVariables.resolvePlaceholders(dev.getProperty("app.admin.password"))).isEqualTo("Demo@1234");

		// whatever ADMIN_EMAIL / ADMIN_PASSWORD the shell has, the account matches the resolved settings
		User admin = users.findByEmailIgnoreCase(env.getProperty("app.admin.email")).orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(passwordEncoder.matches(env.getProperty("app.admin.password"), admin.getPasswordHash())).isTrue();
	}

	@Test
	void devProfileRegistersTheH2Console() {
		assertThat(context.getBeansOfType(ServletRegistrationBean.class).values())
			.anySatisfy(registration -> assertThat(registration.getUrlMappings()).contains("/h2-console/*"));
	}

	@Test
	void devDatabaseIsAnH2FileInMySqlMode() throws Exception {
		Properties dev = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-dev.properties"));
		assertThat(dev.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:h2:file:./data/zaika;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
	}

	@Test
	void uiKitRendersEveryComponentAndIcon() throws Exception {
		MvcResult result = mvc.perform(get("/dev/ui-kit"))
			.andExpect(status().isOk())
			.andExpect(view().name("dev/ui-kit"))
			.andExpect(content().string(allOf(
					containsString("<title>UI kit | Zaika</title>"),
					containsString("<html lang=\"en\">"),
					containsString("href=\"/css/tokens.css\""),
					containsString("href=\"/css/app.css\""),
					containsString("src=\"/img/logo.svg\""),
					containsString("href=\"/img/favicon.svg\""))))
			.andReturn();
		String html = result.getResponse().getContentAsString();

		assertThat(html).contains("id=\"colours\"", "id=\"type\"", "id=\"icons\"", "id=\"buttons\"", "id=\"forms\"",
				"id=\"choices\"", "id=\"cards\"", "id=\"restaurant-cards\"", "id=\"menu-items\"", "id=\"badges\"",
				"id=\"ratings\"", "id=\"tabs\"", "id=\"stepper\"", "id=\"tables\"", "id=\"pagination\"",
				"id=\"feedback\"", "id=\"modal\"", "id=\"empty\"", "id=\"skeletons\"", "id=\"cart-bar\"");
		// every component and state
		assertThat(html).contains("btn btn-primary", "btn btn-secondary", "btn btn-ghost", "btn btn-danger", "btn-sm",
				"btn-lg", " disabled>", "aria-disabled=\"true\"", "is-loading", "class=\"input\"", "class=\"select\"",
				"class=\"textarea\"", "field-help", "field-error", "aria-invalid=\"true\"", "class=\"checkbox\"",
				"class=\"radio\"", "class=\"switch\"", "class=\"card\"", "restaurant-card", "menu-item", "class=\"badge",
				"class=\"chip\"", "rating-pill", "veg-mark non-veg", "class=\"tabs\"", "stepper", "class=\"table\"",
				"class=\"pagination\"", "toast", "alert", "<dialog class=\"modal\"", "command=\"show-modal\"",
				"empty-state", "skeleton", "cart-bar", "class=\"skip-link\"");
		for (String icon : List.of("search", "cart", "user", "location", "clock", "star", "heart", "plus", "minus", "trash",
				"chevron-down", "chevron-right", "check", "x", "menu", "bell", "filter", "bike", "store", "receipt", "logout",
				"edit", "upload", "phone", "info", "leaf")) {
			assertThat(html).contains("href=\"/img/icons.svg#" + icon + "\"");
		}
		// inside the site layout
		assertThat(html).contains("class=\"site-header\"", "class=\"site-footer\"", "id=\"main\"");
		// CSP-ready: scripts only from files (the layout loads htmx and two small scripts), no inline event handlers
		assertThat(html).doesNotContainPattern("<script(?![^>]*\\ssrc=)").doesNotContainPattern(" on[a-z]+=");
	}

	@Test
	void h2ConsoleIsOpenInDevWithRelaxedFramingOnlyThere() throws Exception {
		MvcResult console = mvc.perform(get("/h2-console/")).andReturn();
		assertThat(console.getResponse().getStatus()).as("no login redirect or denial").isNotIn(302, 401, 403);
		assertThat(console.getResponse().getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
		assertThat(console.getResponse().getHeader("Content-Security-Policy")).isEqualTo(SecurityConfig.H2_CONSOLE_POLICY);
		// the console posts its own forms without our CSRF token
		assertThat(mvc.perform(post("/h2-console/login.do")).andReturn().getResponse().getStatus()).isNotIn(302, 401, 403);

		// every other URL keeps the strict policy, including the dev UI kit
		mvc.perform(get("/dev/ui-kit"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY));
	}

}
