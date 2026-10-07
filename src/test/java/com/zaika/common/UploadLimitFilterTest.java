package com.zaika.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Uploads over the container's multipart limits, on a real Tomcat (MockMvc never parses multipart bodies): the
 * visitor lands back on the form with an error toast instead of a bare 413 or a reset connection.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:uploadlimit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class UploadLimitFilterTest {

	static final String FORM = "/partner-with-us/restaurant";

	@LocalServerPort
	int port;

	@Test
	void anUploadOverTheContainerLimitGoesBackToTheFormWithAToast() throws Exception {
		HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
		String base = "http://localhost:" + port;
		String page = client.send(HttpRequest.newBuilder(URI.create(base + FORM)).header("Accept", "text/html").build(),
				HttpResponse.BodyHandlers.ofString()).body();
		Matcher csrf = Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"").matcher(page);
		assertThat(csrf.find()).isTrue();

		String boundary = "----zaikaBoundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf.group(1)
				+ "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"logo\"; filename=\"big.jpg\"\r\n"
				+ "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(new byte[7 * 1024 * 1024]); // over the 5 MB a file / 6 MB a request
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + FORM))
			.header("Accept", "text/html")
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(response.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith(FORM));
		String again = client.send(HttpRequest.newBuilder(URI.create(base + FORM)).header("Accept", "text/html").build(),
				HttpResponse.BodyHandlers.ofString()).body();
		assertThat(again).contains("toast-error", "That file is too big. Images can be up to 2 MB, so choose a smaller one.");
	}

	@Test
	void theRedirectNeverLeavesTheSite() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "//evil.example/x") {
			@Override
			public Collection<Part> getParts() {
				throw new IllegalStateException("too big");
			}
		};
		request.setContentType("multipart/form-data; boundary=x");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new UploadLimitFilter().doFilter(request, response, chain);

		assertThat(response.getRedirectedUrl()).isEqualTo("/evil.example/x");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void otherRequestsPassStraightThrough() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", FORM);
		request.setContentType("application/x-www-form-urlencoded");
		MockFilterChain chain = new MockFilterChain();
		new UploadLimitFilter().doFilter(request, new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isSameAs(request);
	}

}
