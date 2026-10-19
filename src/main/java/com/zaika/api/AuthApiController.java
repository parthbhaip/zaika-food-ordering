package com.zaika.api;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.SmartValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.zaika.security.ApiAuthService;
import com.zaika.security.CurrentUser;
import com.zaika.user.RegistrationForm;
import com.zaika.user.UserService;

/** Sign-up, sign-in and the signed-in account for the mobile app. Errors: {@link ApiExceptionHandler}. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = OpenApiConfig.ACCOUNT)
class AuthApiController {

	private final ApiAuthService auth;

	private final UserService userService;

	private final SmartValidator validator;

	AuthApiController(ApiAuthService auth, UserService userService, SmartValidator validator) {
		this.auth = auth;
		this.userService = userService;
		this.validator = validator;
	}

	/** Creates a customer account and signs it in: 201 with a token, 400 for invalid fields, 409 for a taken email. */
	@Operation(summary = "Sign up as a customer",
			description = "Creates a customer account and signs it in. The password is sent once; the website's rules "
					+ "apply (name, email, Indian mobile number, a password of 8+ characters with a letter and a "
					+ "number, accepted terms). 400 lists every invalid field, 409 = email taken.")
	@ApiResponse(responseCode = "201", description = "Signed up: the token and the new account.")
	@PostMapping("/auth/register")
	ResponseEntity<TokenResponse> register(@RequestBody RegisterRequest body) throws BindException {
		RegistrationForm form = body.toForm();
		BindException errors = new BindException(new BeanPropertyBindingResult(form, "registration"));
		validator.validate(form, errors);
		if (!form.getPassword().isEmpty()) { // an empty password already has its error; skip "type it again"
			form.checkPasswords(errors);
		}
		if (errors.hasErrors()) {
			throw errors;
		}
		return ResponseEntity.created(URI.create("/api/v1/me")).body(TokenResponse.of(auth.register(form)));
	}

	/** 200 with a token; 401 wrong email or password, 429 locked for a while, 403 switched-off account. */
	@Operation(summary = "Sign in", description = "Any role. 401 = wrong email or password; 429 = too many wrong "
			+ "passwords, locked until `lockedUntil` (also `Retry-After`); 403 = the account is switched off.")
	@PostMapping("/auth/login")
	TokenResponse login(@Valid @RequestBody LoginRequest body) {
		return TokenResponse.of(auth.login(body.email(), body.password()));
	}

	@Operation(summary = "The signed-in account")
	@SecurityRequirement(name = OpenApiConfig.BEARER)
	@GetMapping("/me")
	MeResponse me(@AuthenticationPrincipal CurrentUser me) {
		return MeResponse.of(userService.get(me.getId()));
	}

}
