package com.zaika.api;

import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI description of the mobile app's API: {@code /v3/api-docs} (everything) and one group per area at
 * {@code /v3/api-docs/{group}}, browsable at {@code /swagger-ui.html}. Endpoints that need a token carry
 * {@code @SecurityRequirement(name = BEARER)}; every request body gets its example from {@link #REQUEST_EXAMPLES}
 * (kept here so the website's form classes stay free of API annotations). The quick start for the app team is
 * {@code docs/API.md}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	/** Name of the JWT bearer security scheme ("Authorize" in Swagger UI). */
	static final String BEARER = "bearer-token";

	static final String ACCOUNT = "Account";

	static final String CATALOG = "Catalog";

	static final String ADDRESSES = "Customer: addresses";

	static final String CART = "Customer: cart";

	static final String ORDERS = "Customer: orders";

	static final String OWNER = "Restaurant owner";

	static final String RIDER = "Delivery partner";

	/** Component name of the problem-detail schema every error response points at. */
	private static final String PROBLEM = "Problem";

	private static final String DESCRIPTION = """
			The REST API behind the Zaika mobile apps: the same restaurants, carts, orders and rules as the website.

			**Signing in.** `POST /api/v1/auth/register` (new customers) or `POST /api/v1/auth/login` answers with an \
			`accessToken`. Send it on every other call as `Authorization: Bearer <accessToken>`; in this page, press \
			**Authorize** and paste the token. Tokens last 7 days and there is no refresh token: sign in again when a \
			call answers 401. A token stops working early when the password changes or the account is switched off.

			**Areas.** The catalog needs no token. Addresses, cart and orders need a CUSTOMER token, `/owner` a \
			RESTAURANT_OWNER token and `/rider` a DELIVERY_PARTNER token; any other account gets 403.

			**Errors** are RFC 9457 problem details (`application/problem+json`) with a `detail` ready to show. \
			Invalid input adds `errors` (field name -> message); refusals by the rules are 409 and often add a `reason` code.

			**Formats.** Money is a JSON number in rupees with 2 decimals (`249.50`); times are ISO 8601 with the \
			India offset (`2026-10-04T19:30:00+05:30`); pages start at 1.""";

	@Bean
	OpenAPI zaikaOpenApi() {
		return new OpenAPI()
			.info(new Info().title("Zaika API")
				.version("v1")
				.description(DESCRIPTION)
				.contact(new Contact().name("Zaika partner support").email("partners@zaika.test")))
			.components(new Components().addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("The accessToken from POST /api/v1/auth/login or /register.")))
			.tags(List.of(
					tag(ACCOUNT, "Sign up, sign in and the signed-in account."),
					tag(CATALOG, "Cuisines, restaurants with their menus, and search. No token needed."),
					tag(ADDRESSES, "The customer's saved delivery addresses (up to 10, one default)."),
					tag(CART, "The customer's cart: dishes from one restaurant, the kitchen note, one coupon, the "
							+ "bill."),
					tag(ORDERS, "Checkout, order history, live tracking, cancelling and rating."),
					tag(OWNER, "The live orders board, the kitchen's moves, sold out / back in stock per dish and "
							+ "pausing new orders."),
					tag(RIDER, "Online switch, orders waiting for a rider, pick-up, delivery with the customer's code, "
							+ "earnings.")));
	}

	private static final String ADDRESS_EXAMPLE = """
			{"label": "HOME", "house": "Flat 3, Sai Residency", "street": "8th Lane, Rajarampuri",
			 "landmark": "Near Rajarampuri bus stop", "city": "Kolhapur", "pincode": "416008", "makeDefault": true}""";

	/**
	 * Example request bodies by "METHOD path", as springdoc lists the operations. Amounts are parsed as doubles,
	 * so they avoid a trailing zero (438.75, never 438.50) to read right in Swagger UI.
	 */
	static final Map<String, String> REQUEST_EXAMPLES = Map.ofEntries(
			Map.entry("POST /api/v1/auth/register", """
					{"name": "Riya Shinde", "email": "riya.shinde@example.com", "phone": "9823456789",
					 "password": "Kolhapur@2026", "acceptTerms": true}"""),
			Map.entry("POST /api/v1/auth/login", """
					{"email": "customer@zaika.test", "password": "Demo@1234"}"""),
			Map.entry("POST /api/v1/addresses", ADDRESS_EXAMPLE),
			Map.entry("PUT /api/v1/addresses/{id}", ADDRESS_EXAMPLE),
			Map.entry("PUT /api/v1/cart/items/{itemId}", """
					{"quantity": 2, "startNew": false}"""),
			Map.entry("PUT /api/v1/cart/note", """
					{"instructions": "Less spicy, please. Pav on the side."}"""),
			Map.entry("POST /api/v1/cart/coupon", """
					{"code": "WELCOME50"}"""),
			Map.entry("POST /api/v1/orders", """
					{"addressId": 12, "paymentMethod": "COD", "deliveryNote": "Ring the bell twice.",
					 "idempotencyKey": "3f2b8c1e-4d5a-4b6c-9d7e-8f9a0b1c2d3e", "expectedTotal": 438.75}"""),
			Map.entry("POST /api/v1/orders/{code}/cancel", """
					{"reason": "Ordered by mistake", "details": ""}"""),
			Map.entry("POST /api/v1/orders/{code}/review", """
					{"stars": 5, "comment": "The tambda rassa was spot on and arrived piping hot."}"""),
			Map.entry("POST /api/v1/owner/orders/{code}/accept", """
					{"prepMinutes": 20}"""),
			Map.entry("POST /api/v1/owner/orders/{code}/reject", """
					{"reason": "Something else", "details": "Gas cylinder ran out, back in an hour."}"""),
			Map.entry("PUT /api/v1/owner/menu/items/{itemId}/availability", """
					{"available": false}"""),
			Map.entry("PUT /api/v1/owner/accepting-orders", """
					{"acceptingOrders": false}"""),
			Map.entry("PUT /api/v1/rider/online", """
					{"online": true}"""),
			Map.entry("POST /api/v1/rider/orders/{code}/deliver", """
					{"otp": "4821"}"""));

	/** Puts {@link #REQUEST_EXAMPLES} on the request bodies, in the full description and in every group. */
	@Bean
	GlobalOpenApiCustomizer requestExamples(JsonMapper json) {
		return openApi -> openApi.getPaths()
			.forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
				String example = REQUEST_EXAMPLES.get(method + " " + path);
				if (example != null && operation.getRequestBody() != null) {
					Object value = json.readValue(example, Object.class); // a JSON object, not a string
					operation.getRequestBody().getContent().values().forEach(media -> media.setExample(value));
				}
			}));
	}

	/**
	 * Every operation can answer a problem detail ({@code default}); those that need a token also list 401 (no token,
	 * expired or revoked) and 403 (another role's token), which the API chain answers before any controller runs.
	 */
	@Bean
	GlobalOpenApiCustomizer problemResponses() {
		return openApi -> {
			openApi.getComponents().addSchemas(PROBLEM, problemSchema());
			openApi.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
				operation.getResponses().addApiResponse("default", problem("Refused or failed: a problem detail. 400 = "
						+ "invalid input (see errors), 404 = not found (or not yours), 409 = refused by the rules."));
				if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
					operation.getResponses()
						.addApiResponse("401", problem("No token, or it expired or was revoked: sign in again."))
						.addApiResponse("403", problem("A token of another role (say, a customer calling /owner)."));
				}
			}));
		};
	}

	private static ApiResponse problem(String description) {
		return new ApiResponse().description(description)
			.content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
	}

	private static Schema<?> problemSchema() {
		return new ObjectSchema().description("An RFC 9457 problem detail; show detail to the user.")
			.addProperty("type", new StringSchema().example("about:blank"))
			.addProperty("title", new StringSchema().example("Bad Request"))
			.addProperty("status", new IntegerSchema().example(400))
			.addProperty("detail", new StringSchema().example("Some fields need fixing. See errors for each field."))
			.addProperty("instance", new StringSchema().example("/api/v1/addresses"))
			.addProperty("errors", new MapSchema().additionalProperties(new StringSchema())
				.description("Invalid input only: field name -> message.")
				.example(Map.of("pincode", "Enter a valid 6-digit PIN code, like 416008.")))
			.addProperty("reason", new StringSchema()
				.description("Some 409s: a code for the app, e.g. SOLD_OUT, CLOSED, OTHER_RESTAURANT, TOO_LATE."));
	}

	@Bean
	GroupedOpenApi accountApi() {
		return group("account", "Account", "/api/v1/auth/**", "/api/v1/me");
	}

	@Bean
	GroupedOpenApi catalogApi() {
		return group("catalog", "Catalog", "/api/v1/cuisines", "/api/v1/restaurants/**", "/api/v1/search");
	}

	@Bean
	GroupedOpenApi customerApi() {
		return group("customer", "Customer", "/api/v1/addresses/**", "/api/v1/cart/**", "/api/v1/orders/**");
	}

	@Bean
	GroupedOpenApi ownerApi() {
		return group("owner", "Restaurant owner", "/api/v1/owner/**");
	}

	@Bean
	GroupedOpenApi riderApi() {
		return group("rider", "Delivery partner", "/api/v1/rider/**");
	}

	private static GroupedOpenApi group(String name, String displayName, String... paths) {
		return GroupedOpenApi.builder().group(name).displayName(displayName).pathsToMatch(paths).build();
	}

	private static Tag tag(String name, String description) {
		return new Tag().name(name).description(description);
	}

}
