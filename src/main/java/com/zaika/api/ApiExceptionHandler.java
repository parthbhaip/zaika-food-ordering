package com.zaika.api;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.zaika.common.ForbiddenException;
import com.zaika.common.Formatters;
import com.zaika.common.NotFoundException;
import com.zaika.security.ApiSignInException;
import com.zaika.user.EmailTakenException;

/**
 * Every error from an API controller as an RFC 9457 problem detail ({@code application/problem+json}). Ordered
 * first, so it wins over the website's {@link com.zaika.common.ErrorPageAdvice} (branded HTML pages) for the
 * exceptions both know. Spring MVC's own errors (unsupported media type, wrong method, ...) come from the base class.
 * Invalid input adds {@code errors}: field name -> message.
 */
@RestControllerAdvice(basePackages = "com.zaika.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final Clock clock;

	private final Formatters fmt;

	ApiExceptionHandler(Clock clock, Formatters fmt) {
		this.clock = clock;
		this.fmt = fmt;
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return ResponseEntity.badRequest().body(invalid(ex.getBindingResult()));
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return ResponseEntity.badRequest()
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is missing or is not valid JSON."));
	}

	/** Input checked by hand in a controller (sign-up uses the website's form rules). */
	@ExceptionHandler(BindException.class)
	ProblemDetail bindFailed(BindException ex) {
		return invalid(ex.getBindingResult());
	}

	@ExceptionHandler(EmailTakenException.class)
	ProblemDetail emailTaken() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"This email already has a Zaika account. Sign in instead.");
		problem.setProperty("errors", Map.of("email", "This email already has a Zaika account. Sign in instead."));
		return problem;
	}

	@ExceptionHandler(ApiSignInException.class)
	ResponseEntity<ProblemDetail> signInRefused(ApiSignInException ex) {
		return switch (ex.getReason()) {
			case WRONG_PASSWORD -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
						"That email and password don't match. Check both and try again."));
			case DISABLED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
						"This account is switched off. Write to support@zaika.test and we'll help you get back in."));
			case LOCKED -> locked(ex.getLockedUntil());
		};
	}

	private ResponseEntity<ProblemDetail> locked(LocalDateTime until) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				"Too many wrong passwords. To keep your account safe, sign-in is paused until " + fmt.time(until)
						+ ". Reset your password on the website to get back in right away.");
		problem.setProperty("lockedUntil", until.atZone(clock.getZone()).toOffsetDateTime());
		long seconds = Math.max(1, Duration.between(LocalDateTime.now(clock), until).toSeconds());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
			.body(problem);
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException ex) {
		log.debug("404: {}", ex.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "We couldn't find that.");
	}

	@ExceptionHandler(ForbiddenException.class)
	ProblemDetail forbidden(ForbiddenException ex) {
		log.debug("403: {}", ex.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Your account can't do that.");
	}

	/** Anything unexpected: logged here, never shown (no stack traces or SQL in answers). */
	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception ex) {
		log.error("API request failed", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"Something went wrong on our side. Try again in a moment.");
	}

	private static ProblemDetail invalid(BindingResult result) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : result.getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Some fields need fixing. See errors for each field.");
		problem.setProperty("errors", errors);
		return problem;
	}

}
