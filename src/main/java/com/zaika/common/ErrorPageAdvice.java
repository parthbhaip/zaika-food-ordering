package com.zaika.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * Maps the app's own exceptions to the branded error pages in {@code templates/error/}. Everything else
 * (unknown URLs, Spring Security's access denied, unexpected exceptions) reaches the same templates through
 * Spring Boot's {@code /error} handling, so there is no catch-all here.
 */
@ControllerAdvice
public class ErrorPageAdvice {

	private static final Logger log = LoggerFactory.getLogger(ErrorPageAdvice.class);

	@ExceptionHandler(NotFoundException.class)
	public ModelAndView notFound(NotFoundException ex) {
		log.debug("404: {}", ex.getMessage());
		return errorPage(HttpStatus.NOT_FOUND);
	}

	@ExceptionHandler(ForbiddenException.class)
	public ModelAndView forbidden(ForbiddenException ex) {
		log.debug("403: {}", ex.getMessage());
		return errorPage(HttpStatus.FORBIDDEN);
	}

	private static ModelAndView errorPage(HttpStatus status) {
		ModelAndView page = new ModelAndView("error/" + status.value(), status);
		page.addObject("status", status.value());
		return page;
	}

}
