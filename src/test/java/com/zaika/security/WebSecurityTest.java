package com.zaika.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.zaika.user.Role;

/**
 * URL rules: public pages open to everyone, each members-only area only for its role (403 for the others, login for
 * anonymous visitors), everything unlisted needs a sign-in. A role in its own area is never redirected or denied
 * (pages not built yet answer 404, built ones 200).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WebSecurityTest {

	/** One URL per members-only area, with the role it belongs to. */
	static final Map<String, Role> AREA_URLS = Map.of(
			"/account", Role.CUSTOMER,
			"/account/addresses/3", Role.CUSTOMER,
			"/checkout", Role.CUSTOMER,
			"/orders/ZK-26-000123", Role.CUSTOMER,
			"/pay/ZK-26-000123", Role.CUSTOMER,
			"/owner/menu", Role.RESTAURANT_OWNER,
			"/partner", Role.DELIVERY_PARTNER,
			"/partner/earnings", Role.DELIVERY_PARTNER,
			"/admin", Role.ADMIN,
			"/admin/restaurants", Role.ADMIN);

	static final List<String> PUBLIC_URLS = List.of("/", "/restaurants", "/restaurants?veg=true", "/r/misal-house",
			"/search?q=misal", "/cart", "/login", "/register", "/forgot-password", "/reset-password?token=abc",
			"/partner-with-us", "/partner-with-us/restaurant", "/partner-with-us/rider", "/about", "/contact", "/terms",
			"/privacy", "/refund-policy", "/css/app.css", "/js/menus.js", "/img/logo.svg", "/fonts/OFL.txt",
			"/webjars/htmx.org/dist/htmx.min.js", "/uploads/menu/abc.jpg", "/placeholder/restaurant/misal.svg");

	@Autowired
	MockMvc mvc;

	@Test
	void anonymousVisitorsAreSentToTheLoginPageFromEveryMembersArea() throws Exception {
		for (String url : AREA_URLS.keySet()) {
			mvc.perform(get(url)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		}
	}

	@Test
	void eachRoleGets403InOtherRolesAreasAndIsLetIntoItsOwn() throws Exception {
		for (Role role : Role.values()) {
			for (Map.Entry<String, Role> area : AREA_URLS.entrySet()) {
				int status = mvc.perform(get(area.getKey()).with(user(new CurrentUser(Long.MAX_VALUE, "someone@zaika.test", "unused", true, role))))
					.andReturn().getResponse().getStatus();
				if (area.getValue() == role) {
					assertThat(status).as("%s in %s", role, area.getKey()).isNotIn(302, 401, 403);
				}
				else {
					assertThat(status).as("%s in %s", role, area.getKey()).isEqualTo(403);
				}
			}
		}
	}

	@Test
	void publicPagesAndAssetsNeedNoSignIn() throws Exception {
		for (String url : PUBLIC_URLS) {
			int status = mvc.perform(get(url)).andReturn().getResponse().getStatus();
			// pages built in later tasks answer 404 for now; what matters is no login redirect and no 403
			assertThat(status).as(url).isIn(200, 404);
		}
		mvc.perform(get("/login")).andExpect(status().isOk());
		mvc.perform(get("/css/app.css")).andExpect(status().isOk());
	}

	@Test
	void areaPrefixesDoNotSwallowLookAlikeUrls() throws Exception {
		// /partner-with-us is public even though it starts with "/partner"
		mvc.perform(get("/partner-with-us/rider").with(user("c@zaika.test").roles("CUSTOMER")))
			.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(302, 401, 403));
		assertThat(SecurityConfig.areaOf("/partner-with-us")).isNull();
		assertThat(SecurityConfig.areaOf("/ordersheet")).isNull();
		assertThat(SecurityConfig.areaOf("/orders")).isEqualTo(Role.CUSTOMER);
		assertThat(SecurityConfig.areaOf("/orders/ZK-1/track")).isEqualTo(Role.CUSTOMER);
		assertThat(SecurityConfig.areaOf("/partner/earnings")).isEqualTo(Role.DELIVERY_PARTNER);
		assertThat(SecurityConfig.areaOf("/admin")).isEqualTo(Role.ADMIN);
	}

	@Test
	void unlistedUrlsNeedASignIn() throws Exception {
		mvc.perform(get("/notifications")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/actuator/health")).andExpect(status().isFound());
	}

	@Test
	void devToolsAreClosedOutsideTheDevProfile() throws Exception {
		mvc.perform(get("/dev/ui-kit")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/h2-console/")).andExpect(status().isFound());
	}

	@Test
	void anonymousNotFoundOnAPublicPathIsA404NotALoginRedirect() throws Exception {
		mvc.perform(get("/r/no-such-place/extra")).andExpect(status().isNotFound());
	}

	@Test
	void errorDispatchesRenderTheBrandedPageForAnonymousVisitors() throws Exception {
		mvc.perform(get("/error").accept(MediaType.TEXT_HTML)
				.with(request -> {
					request.setDispatcherType(DispatcherType.ERROR);
					return request;
				})
				.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
				.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/no-such-page"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"));
	}

	@Test
	void securityHeadersAreOnPublicPagesAndOnDenials() throws Exception {
		mvc.perform(get("/login"))
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
		mvc.perform(get("/admin").with(user("c@zaika.test").roles("CUSTOMER")))
			.andExpect(status().isForbidden())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Frame-Options", "DENY"));
	}

}
