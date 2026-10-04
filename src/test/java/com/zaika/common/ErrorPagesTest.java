package com.zaika.common;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.zaika.support.MutableClock;

/**
 * Branded error pages: the app's own exceptions go through {@link ErrorPageAdvice}; anything else (unknown URL,
 * access denied, crash) is forwarded by the servlet container to Boot's {@code /error}, simulated here with the
 * standard error request attributes. Also renders a probe view that uses the {@code fmt} bean.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ErrorPagesTest.ProbeController.class)
@WithMockUser
class ErrorPagesTest {

	@Autowired
	MockMvc mvc;

	@Test
	void notFoundExceptionRendersTheBranded404Page() throws Exception {
		mvc.perform(get("/probe/missing-dish"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(allOf(
					containsString("<title>Page not found | Zaika</title>"),
					containsString("This page wandered off the menu"),
					containsString("href=\"/\">Back to home</a>"),
					containsString("<html lang=\"en\">"),
					containsString("href=\"/css/app.css\""),
					not(containsString("Dish 42")))));
	}

	@Test
	void forbiddenExceptionRendersTheBranded403Page() throws Exception {
		mvc.perform(get("/probe/someone-elses-order"))
			.andExpect(status().isForbidden())
			.andExpect(view().name("error/403"))
			.andExpect(content().string(allOf(
					containsString("<title>Access denied | Zaika</title>"),
					containsString("This kitchen is staff-only"),
					containsString("href=\"/\">Back to home</a>"),
					containsString("href=\"/login\">Sign in with another account</a>"),
					not(containsString("order 7")))));
	}

	@Test
	void unknownUrlForwardedToErrorRendersThe404Page() throws Exception {
		mvc.perform(errorForward(404, "/restaurants/no-such-place"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"))
			.andExpect(content().string(containsString("This page wandered off the menu")));
	}

	@Test
	void accessDeniedForwardedToErrorRendersThe403Page() throws Exception {
		mvc.perform(errorForward(403, "/admin"))
			.andExpect(status().isForbidden())
			.andExpect(view().name("error/403"))
			.andExpect(content().string(containsString("This kitchen is staff-only")));
	}

	@Test
	void crashRendersThe500PageWithoutLeakingDetails() throws Exception {
		mvc.perform(errorForward(500, "/checkout")
				.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("db password is hunter2")))
			.andExpect(status().isInternalServerError())
			.andExpect(view().name("error/500"))
			.andExpect(content().string(allOf(
					containsString("<title>Something went wrong | Zaika</title>"),
					containsString("Something got burnt in our kitchen"),
					containsString("href=\"/\">Back to home</a>"),
					not(containsString("hunter2")),
					not(containsString("IllegalStateException")),
					not(containsString("Sign in with another account")))));
	}

	@Test
	void otherClientErrorsUseTheGenericBrandedPage() throws Exception {
		mvc.perform(errorForward(405, "/cart"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(view().name("error"))
			.andExpect(content().string(allOf(
					containsString("<title>Request not understood | Zaika</title>"),
					containsString(">405<"),
					containsString("That request didn’t go as planned"),
					containsString("href=\"/\">Back to home</a>"))));
	}

	@Test
	void otherServerErrorsUseTheGenericBrandedPage() throws Exception {
		mvc.perform(errorForward(503, "/"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(view().name("error"))
			.andExpect(content().string(allOf(
					containsString(">503<"),
					containsString("Something got burnt in our kitchen"))));
	}

	@Test
	void fmtBeanIsUsableFromTemplates() throws Exception {
		mvc.perform(get("/probe/fmt"))
			.andExpect(status().isOk())
			.andExpect(content().string(allOf(
					containsString("<p id=\"money\">₹1,23,456.50</p>"),
					containsString("<p id=\"time\">7:25 PM</p>"),
					containsString("<p id=\"date-time\">4 Oct 2026, 7:25 PM</p>"),
					containsString("<p id=\"relative\">5 min ago</p>"))));
	}

	private static MockHttpServletRequestBuilder errorForward(int status, String originalUri) {
		return get("/error").accept(MediaType.TEXT_HTML)
			.requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
			.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
	}

	@Controller
	static class ProbeController {

		@GetMapping("/probe/missing-dish")
		String missingDish() {
			throw new NotFoundException("Dish 42 not found");
		}

		@GetMapping("/probe/someone-elses-order")
		String someoneElsesOrder() {
			throw new ForbiddenException("order 7 belongs to another customer");
		}

		@GetMapping("/probe/fmt")
		String fmt(Model model) {
			LocalDateTime fiveMinutesAgo = MutableClock.DEFAULT_NOW.minusMinutes(5);
			model.addAttribute("total", new BigDecimal("123456.5"));
			model.addAttribute("placedAt", fiveMinutesAgo);
			return "probe/fmt";
		}

	}

}
