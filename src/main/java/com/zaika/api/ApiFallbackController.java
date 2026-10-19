package com.zaika.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Any /api URL no endpoint answers: a 404 problem detail instead of the website's error page. Real endpoints always
 * win (more specific patterns); a known URL called with the wrong method also lands here.
 */
@RestController
class ApiFallbackController {

	@RequestMapping("/api/**")
	void unknown() {
		throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no API endpoint at this address.");
	}

}
