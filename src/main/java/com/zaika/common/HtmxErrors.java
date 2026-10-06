package com.zaika.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Errors in answer to an htmx request ({@code HX-Request}). htmx swaps nothing for a 4xx or 5xx answer, so a failed
 * click (a dish archived meanwhile, a stale CSRF token) would do nothing visible. Instead the answer, with its error
 * status, is an error toast for the toast stack ({@code HX-Retarget: #toasts}, {@code HX-Reswap: beforeend}), which
 * {@code js/htmx-config.js} lets htmx swap in. Covers the app's own exceptions ({@link ErrorPageAdvice}) and everything
 * Spring Boot's {@code /error} handles (Spring Security's 403, 400s, crashes). A request that needs a sign-in gets
 * 401 with {@code HX-Redirect: /login} from SecurityConfig instead.
 */
@Controller
public class HtmxErrors {

	static boolean isHtmx(HttpServletRequest request) {
		return request.getHeader("HX-Request") != null;
	}

	/** The error toast for {@code status}. */
	static ModelAndView toast(HttpStatusCode status, HttpServletResponse response) {
		response.setHeader("HX-Retarget", "#toasts");
		response.setHeader("HX-Reswap", "beforeend");
		ModelAndView toast = new ModelAndView("fragments/flash :: htmx-error", status);
		toast.addObject("message", switch (status.value()) {
			case 403 -> "That didn’t go through. Refresh the page and try again.";
			case 404 -> "That’s no longer available. Refresh the page to see what’s changed.";
			default -> status.is4xxClientError() ? "That didn’t go through. Refresh the page and try again."
					: "Something went wrong on our side. Please try again in a moment.";
		});
		return toast;
	}

	/** Boot's error dispatch for htmx requests (more specific than its own {@code /error} by the header). */
	@RequestMapping(path = "/error", headers = "HX-Request")
	ModelAndView error(HttpServletRequest request, HttpServletResponse response) {
		Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		return toast(HttpStatusCode.valueOf(status instanceof Integer code ? code : 500), response);
	}

}
