package com.zaika.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;
import com.zaika.security.SecurityConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * T50: the OpenAPI description at /v3/api-docs (+ one group per area), Swagger UI at /swagger-ui.html, and docs/API.md
 * kept in step with the real endpoints. Reuses the default test context (ApiSecurityTest's).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SuppressWarnings("unchecked")
class ApiDocsTest {

	private static final Set<String> GROUPS = Set.of("account", "catalog", "customer", "owner", "rider");

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	private Map<String, Object> doc;

	@BeforeEach
	void loadDescription() throws Exception {
		doc = JsonPath.read(mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$");
	}

	@Test
	void describesTheApiWithJwtBearerAuth() {
		assertThat(read("$.openapi", String.class)).startsWith("3.");
		assertThat(read("$.info.title", String.class)).isEqualTo("Zaika API");
		assertThat(read("$.info.version", String.class)).isEqualTo("v1");
		assertThat(read("$.info.description", String.class)).contains("Authorization: Bearer", "problem details");
		Map<String, Object> scheme = read("$.components.securitySchemes['bearer-token']", Map.class);
		assertThat(scheme).containsEntry("type", "http").containsEntry("scheme", "bearer").containsEntry("bearerFormat",
				"JWT");
	}

	/** Every API endpoint the app answers is described, and nothing else (no website pages, no /api/** fallback). */
	@Test
	void documentsExactlyTheApiEndpoints() {
		Set<String> served = new TreeSet<>();
		mappings.getHandlerMethods().forEach((info, method) -> {
			if (method.getBeanType().getPackageName().equals("com.zaika.api")
					&& method.getBeanType() != ApiFallbackController.class) {
				info.getPathPatternsCondition().getPatternValues().forEach(path -> info.getMethodsCondition()
					.getMethods()
					.forEach(verb -> served.add(verb.name() + " " + path)));
			}
		});
		assertThat(served).hasSizeGreaterThan(40);
		assertThat(operations()).isEqualTo(served);
	}

	@Test
	void everyOperationHasATagASummaryAndAUniqueOperationId() {
		List<String> tagNames = read("$.tags[*].name", List.class);
		assertThat(tagNames).containsExactly("Account", "Catalog", "Customer: addresses", "Customer: cart",
				"Customer: orders", "Restaurant owner", "Delivery partner");
		List<String> ids = new ArrayList<>();
		forEachOperation((key, op) -> {
			assertThat((List<String>) op.get("tags")).as(key).hasSize(1).allMatch(tagNames::contains);
			assertThat((String) op.get("summary")).as(key).isNotBlank();
			ids.add((String) op.get("operationId"));
		});
		assertThat(ids).doesNotHaveDuplicates().noneMatch(id -> id.matches(".*_\\d+"));
	}

	/** Public endpoints carry no lock; everything else needs the bearer token and lists 401/403 problems. */
	@Test
	void tokenRequirementMatchesTheApiSecurityRules() {
		Set<String> publicOnes = Set.of("POST /api/v1/auth/register", "POST /api/v1/auth/login", "GET /api/v1/cuisines",
				"GET /api/v1/restaurants", "GET /api/v1/restaurants/{slug}", "GET /api/v1/search");
		forEachOperation((key, op) -> {
			Map<String, Object> responses = (Map<String, Object>) op.get("responses");
			assertThat(responses).as(key).containsKey("default");
			if (publicOnes.contains(key)) {
				assertThat(op).as(key).doesNotContainKey("security");
				assertThat(responses).as(key).doesNotContainKeys("401", "403");
			}
			else {
				assertThat(op.get("security")).as(key).isEqualTo(List.of(Map.of("bearer-token", List.of())));
				assertThat(responses).as(key).containsKeys("401", "403");
			}
		});
		assertThat(read("$.components.schemas.Problem.properties", Map.class)).containsKeys("type", "title", "status",
				"detail", "instance", "errors", "reason");
		assertThat(read("$.paths['/api/v1/me'].get.responses['401'].content['application/problem+json'].schema['$ref']",
				String.class)).isEqualTo("#/components/schemas/Problem");
	}

	/** Every request body has a JSON object example holding at least every required field of its schema. */
	@Test
	void everyRequestBodyHasAnExamplePayload() {
		List<String> withBody = new ArrayList<>();
		forEachOperation((key, op) -> {
			Map<String, Object> body = (Map<String, Object>) op.get("requestBody");
			if (body == null) {
				return;
			}
			withBody.add(key);
			Map<String, Object> media = (Map<String, Object>) ((Map<String, Object>) body.get("content"))
				.get("application/json");
			assertThat(media).as(key).isNotNull();
			assertThat(media.get("example")).as(key).isInstanceOf(Map.class);
			Map<String, Object> example = (Map<String, Object>) media.get("example");
			String ref = (String) ((Map<String, Object>) media.get("schema")).get("$ref");
			Map<String, Object> schema = read("$.components.schemas." + ref.substring(ref.lastIndexOf('/') + 1),
					Map.class);
			List<String> required = (List<String>) schema.getOrDefault("required", List.of());
			assertThat(example.keySet()).as(key).containsAll(required);
			assertThat(((Map<String, Object>) schema.get("properties")).keySet()).as(key)
				.containsAll(example.keySet());
		});
		assertThat(withBody).hasSize(OpenApiConfig.REQUEST_EXAMPLES.size());
		assertThat(read("$.paths['/api/v1/auth/login'].post.requestBody.content['application/json'].example",
				Map.class)).isEqualTo(Map.of("email", "customer@zaika.test", "password", "Demo@1234"));
	}

	/** The sign-up and address examples are real payloads: the API accepts them as they are. */
	@Test
	void examplePayloadsWorkAgainstTheApi() throws Exception {
		String register = json.writeValueAsString(
				read("$.paths['/api/v1/auth/register'].post.requestBody.content['application/json'].example", Map.class));
		String token = JsonPath.read(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content(register)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
				"$.accessToken");
		String address = json.writeValueAsString(
				read("$.paths['/api/v1/addresses'].post.requestBody.content['application/json'].example", Map.class));
		mvc.perform(post("/api/v1/addresses").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(address))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.pincode").value("416008"))
			.andExpect(jsonPath("$.defaultAddress").value(true));
	}

	@Test
	void oneGroupPerAreaHoldingOnlyItsEndpoints() throws Exception {
		Map<String, String> prefixes = Map.of("account", "/api/v1/(auth/|me$)", "catalog",
				"/api/v1/(cuisines|restaurants|search)", "customer", "/api/v1/(addresses|cart|orders)", "owner",
				"/api/v1/owner/", "rider", "/api/v1/rider/");
		Set<String> all = new TreeSet<>();
		for (String group : GROUPS) {
			Map<String, Object> paths = JsonPath.read(mvc.perform(get("/v3/api-docs/" + group))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.paths");
			assertThat(paths).as(group).isNotEmpty();
			assertThat(paths.keySet()).as(group).allMatch(path -> Pattern.compile("^" + prefixes.get(group)).matcher(path)
				.find());
			assertThat(paths.keySet()).as(group).noneMatch(all::contains); // no endpoint in two groups
			all.addAll(paths.keySet());
		}
		assertThat(all).isEqualTo(((Map<String, Object>) doc.get("paths")).keySet());

		mvc.perform(get("/v3/api-docs/swagger-config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.urls[*].name").value(containsInAnyOrder("Account", "Catalog",
					"Customer", "Restaurant owner", "Delivery partner")))
			.andExpect(jsonPath("$.validatorUrl").value("none"))
			.andExpect(jsonPath("$.persistAuthorization").value(true));
	}

	/** Swagger UI is public, served from our origin under the site's CSP (no inline scripts needed). */
	@Test
	void swaggerUiIsPublicAndRunsUnderTheSiteCsp() throws Exception {
		mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/swagger-ui/index.html"));
		String page = mvc.perform(get("/swagger-ui/index.html"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page).contains("swagger-ui-bundle.js", "swagger-initializer.js");
		Matcher scripts = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>").matcher(page);
		assertThat(scripts.find()).as("inline script").isFalse();
		assertThat(mvc.perform(get("/swagger-ui/swagger-initializer.js"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()).contains("/v3/api-docs/swagger-config");
		mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());

		// signed-in website visitors of any role see the same docs
		mvc.perform(get("/v3/api-docs").with(user("owner@zaika.test").roles("RESTAURANT_OWNER")))
			.andExpect(status().isOk());
	}

	/** docs/API.md lists every endpoint exactly as the description does, so the quick start never goes stale. */
	@Test
	void quickStartListsEveryEndpoint() throws Exception {
		String quickStart = Files.readString(Path.of("docs/API.md"));
		assertThat(quickStart).contains("/swagger-ui.html", "/v3/api-docs", "POST /api/v1/auth/register",
				"POST /api/v1/auth/login", "Authorization: Bearer");
		Set<String> listed = new TreeSet<>();
		Matcher row = Pattern.compile("^\\| `([A-Z]+ /api/v1/[^`]+)` \\|", Pattern.MULTILINE).matcher(quickStart);
		while (row.find()) {
			listed.add(row.group(1));
		}
		assertThat(listed).isEqualTo(operations());
	}

	private Set<String> operations() {
		Set<String> keys = new TreeSet<>();
		forEachOperation((key, op) -> keys.add(key));
		return keys;
	}

	private void forEachOperation(BiConsumer<String, Map<String, Object>> action) {
		((Map<String, Map<String, Object>>) doc.get("paths")).forEach((path, item) -> item.forEach((verb, op) -> action
			.accept(verb.toUpperCase(Locale.ROOT) + " " + path, (Map<String, Object>) op)));
	}

	private <T> T read(String path, Class<T> type) {
		return type.cast(JsonPath.read(doc, path));
	}

}
