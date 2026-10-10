package com.zaika.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.zaika.config.AppProperties;

class StorageServiceTest {

	static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1);
	static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R');
	static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ');

	@TempDir
	Path temp;

	Path uploads;

	StorageService storage;

	@BeforeEach
	void setUp() {
		uploads = temp.resolve("uploads");
		storage = new StorageService(new AppProperties("Zaika", ZoneId.of("Asia/Kolkata"), uploads.toString(), false,
				new BigDecimal("5.00"), new BigDecimal("0.05"), new BigDecimal("499.00"), new BigDecimal("30.00"),
				new BigDecimal("0.80"), "http://localhost:8080"));
	}

	@Test
	void createsTheUploadDirectoryOnStartup() {
		assertThat(uploads).isDirectory();
		assertThat(storage.root()).isEqualTo(uploads.toAbsolutePath().normalize());
	}

	@Test
	void storesJpgPngAndWebpUnderTheKindsFolderWithARandomName() throws Exception {
		String jpg = storage.store(ImageKind.DISH, file("misal.jpg", "image/jpeg", JPEG));
		String png = storage.store(ImageKind.LOGO, file("logo.png", "image/png", PNG));
		String webp = storage.store(ImageKind.COVER, file("front.webp", "image/webp", WEBP));

		assertThat(jpg).matches("dish/[0-9a-f-]{36}\\.jpg");
		assertThat(png).matches("logo/[0-9a-f-]{36}\\.png");
		assertThat(webp).matches("cover/[0-9a-f-]{36}\\.webp");
		assertThat(uploads.resolve(jpg)).hasBinaryContent(JPEG);
		assertThat(uploads.resolve(png)).hasBinaryContent(PNG);
		assertThat(uploads.resolve(webp)).hasBinaryContent(WEBP);
	}

	@Test
	void theSameFileTwiceGetsTwoNamesAndTheOriginalNameIsNeverUsed() {
		String first = storage.store(ImageKind.DISH, file("../../evil name.jpg", "image/jpeg", JPEG));
		String second = storage.store(ImageKind.DISH, file("../../evil name.jpg", "image/jpeg", JPEG));
		assertThat(first).isNotEqualTo(second).doesNotContain("evil");
		assertThat(uploads.resolve(first)).exists();
		assertThat(uploads.resolve(second)).exists();
	}

	@Test
	void theBytesDecideTheTypeNotTheNameOrContentType() {
		assertThat(storage.store(ImageKind.DISH, file("photo.jpg", "image/jpeg", PNG))).endsWith(".png");
	}

	@Test
	void rejectsAnythingThatIsNotJpgPngOrWebp() throws Exception {
		byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
		byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.US_ASCII);
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.US_ASCII);
		byte[] wav = bytes('R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E');
		byte[] shortJpeg = bytes(0xFF, 0xD8);
		for (byte[] content : new byte[][] { gif, html, svg, wav, shortJpeg }) {
			assertThatThrownBy(() -> storage.store(ImageKind.DISH, file("photo.jpg", "image/jpeg", content)))
				.isInstanceOf(InvalidImageException.class)
				.hasMessage("Choose a JPG, PNG or WebP image.");
		}
		assertThat(filesUnder(uploads)).as("nothing is written for a rejected upload").isZero();
	}

	@Test
	void acceptsUpToTwoMegabytesAndRefusesMore() throws Exception {
		byte[] limit = Arrays.copyOf(JPEG, (int) StorageService.MAX_BYTES);
		byte[] over = Arrays.copyOf(JPEG, (int) StorageService.MAX_BYTES + 1);

		assertThat(storage.store(ImageKind.COVER, file("big.jpg", "image/jpeg", limit))).endsWith(".jpg");
		assertThatThrownBy(() -> storage.store(ImageKind.COVER, file("huge.jpg", "image/jpeg", over)))
			.isInstanceOf(InvalidImageException.class)
			.hasMessage("That image is over 2 MB. Choose a smaller one.");
		assertThat(filesUnder(uploads)).isEqualTo(1);
	}

	@Test
	void aMissingOrEmptyFileAsksForAnImage() {
		assertThatThrownBy(() -> storage.store(ImageKind.LOGO, null)).isInstanceOf(InvalidImageException.class)
			.hasMessage("Choose an image to upload.");
		assertThatThrownBy(() -> storage.store(ImageKind.LOGO, file("", "application/octet-stream", new byte[0])))
			.isInstanceOf(InvalidImageException.class)
			.hasMessage("Choose an image to upload.");
	}

	@Test
	void deleteRemovesAStoredImageAndNeverAnythingOutsideTheUploadDirectory() throws Exception {
		String stored = storage.store(ImageKind.DISH, file("misal.jpg", "image/jpeg", JPEG));
		Path outside = Files.writeString(temp.resolve("keep.txt"), "not an upload");

		storage.delete(stored);
		storage.delete("../keep.txt");
		storage.delete(outside.toAbsolutePath().toString());
		storage.delete("");
		storage.delete(".");
		storage.delete(null);
		storage.delete("dish/does-not-exist.jpg");
		storage.delete("dish/bad\0name.jpg"); // InvalidPathException on every file system

		assertThat(uploads.resolve(stored)).doesNotExist();
		assertThat(outside).exists();
		assertThat(uploads).isDirectory();
	}

	static MockMultipartFile file(String name, String contentType, byte[] content) {
		return new MockMultipartFile("image", name, contentType, content);
	}

	static byte[] bytes(int... values) {
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = (byte) values[i];
		}
		return out;
	}

	private static long filesUnder(Path dir) throws Exception {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).count();
		}
	}

}
