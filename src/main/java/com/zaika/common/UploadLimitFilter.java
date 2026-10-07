package com.zaika.common;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * Uploads bigger than the container accepts ({@code spring.servlet.multipart.*}: 5 MB a file, 6 MB a request) never
 * reach a form's own 2 MB check: the container refuses to read the body, so even the CSRF token in it is lost and the
 * visitor would get a bare 413 page. This filter runs before Spring Security, notices that and sends the visitor back
 * to the form's page (every upload form posts to the URL that shows it) with an error toast. Nothing is changed, so
 * skipping the CSRF check for this redirect is safe. {@code server.tomcat.max-swallow-size} lets Tomcat read the rest
 * of the body, so the browser sees the redirect instead of a reset connection.
 */
@Component
@Order(-110) // before Spring Security's filter chain (-100)
public class UploadLimitFilter extends OncePerRequestFilter {

	public static final String UPLOAD_TOO_BIG = "That file is too big. Images can be up to 2 MB, so choose a smaller one.";

	private static final Logger log = LoggerFactory.getLogger(UploadLimitFilter.class);

	private final SessionFlashMapManager flashMaps = new SessionFlashMapManager();

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String type = request.getContentType();
		return !"POST".equals(request.getMethod()) || type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			request.getParts();
		}
		catch (IllegalStateException ex) {
			// the Servlet spec's signal for a body or a part over the multipart limits
			log.debug("Upload over the container limits on {}: {}", request.getRequestURI(), ex.getMessage());
			FlashMap flash = new FlashMap();
			flash.put("flashError", UPLOAD_TOO_BIG);
			flashMaps.saveOutputFlashMap(flash, request, response);
			// a leading "//" or "/\" would make the browser leave the site
			response.sendRedirect(request.getRequestURI().replaceFirst("^[/\\\\]+", "/"));
			return;
		}
		chain.doFilter(request, response);
	}

}
