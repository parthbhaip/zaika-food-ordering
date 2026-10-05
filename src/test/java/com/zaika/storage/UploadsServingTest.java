package com.zaika.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Stored uploads are served to anyone at /uploads/** with long-lived cache headers, and nothing else is. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UploadsServingTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	StorageService storage;

	@Autowired
	MultipartProperties multipart;

	final List<String> stored = new ArrayList<>();

	@AfterEach
	void removeStoredFiles() {
		stored.forEach(storage::delete);
	}

	@Test
	void uploadsLiveInTheConfiguredDirectoryOutsideTheClasspath() {
		assertThat(storage.root()).isEqualTo(Path.of("target", "test-uploads").toAbsolutePath().normalize());
	}

	@Test
	void aStoredImageIsServedToAnonymousVisitorsWithAYearLongImmutableCache() throws Exception {
		String png = store(ImageKind.DISH, StorageServiceTest.PNG, "image/png");
		String webp = store(ImageKind.COVER, StorageServiceTest.WEBP, "image/webp");

		mvc.perform(get(StorageService.URL_PREFIX + png))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"))
			.andExpect(content().bytes(StorageServiceTest.PNG))
			.andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"));
		mvc.perform(get(StorageService.URL_PREFIX + webp))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/webp"));
	}

	@Test
	void missingFilesAndPathsOutsideTheUploadDirectoryAreNotServed() throws Exception {
		mvc.perform(get("/uploads/dish/does-not-exist.jpg")).andExpect(status().isNotFound());
		for (String url : List.of("/uploads/../classes/application.properties", "/uploads/%2e%2e/classes/application.properties",
				"/uploads/..%2fclasses%2fapplication.properties", "/uploads/")) {
			int code = mvc.perform(get(url)).andReturn().getResponse().getStatus();
			assertThat(code).as(url).isBetween(400, 499);
		}
	}

	@Test
	void theContainerAcceptsUploadsUpToTheTwoMegabyteRuleSoTheFriendlyMessageWins() {
		assertThat(multipart.getMaxFileSize().toBytes()).isGreaterThan(StorageService.MAX_BYTES);
		assertThat(multipart.getMaxRequestSize().toBytes()).isGreaterThan(multipart.getMaxFileSize().toBytes());
	}

	private String store(ImageKind kind, byte[] bytes, String contentType) {
		String path = storage.store(kind, StorageServiceTest.file("upload", contentType, bytes));
		stored.add(path);
		return path;
	}

}
