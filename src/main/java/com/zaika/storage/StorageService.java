package com.zaika.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.zaika.config.AppProperties;

/**
 * Saves uploaded images under {@code app.upload-dir} (outside the classpath), served back at {@code /uploads/**}.
 * Only JPG, PNG and WebP are accepted, judged by the file's first bytes, never by its name or the browser's content
 * type. Files get random names, so a stored image never changes and can be cached for a year.
 */
@Service
public class StorageService {

	/** Uploads up to and including this size are accepted. */
	public static final long MAX_BYTES = 2L * 1024 * 1024;

	/** Where stored images are served; a stored path {@code dish/ab12.jpg} is at {@code /uploads/dish/ab12.jpg}. */
	public static final String URL_PREFIX = "/uploads/";

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
	private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);

	private final Path root;

	public StorageService(AppProperties properties) {
		this.root = Path.of(properties.uploadDir()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot create the upload directory " + root, ex);
		}
	}

	/** The absolute upload directory. */
	public Path root() {
		return root;
	}

	/**
	 * Checks and saves an image.
	 *
	 * @return the stored path relative to the upload directory, like {@code dish/0f8c...e2.webp}; save this on the entity
	 * @throws InvalidImageException when the file is missing, over 2 MB, or not a JPG, PNG or WebP image
	 */
	public String store(ImageKind kind, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new InvalidImageException("Choose an image to upload.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw new InvalidImageException("That image is over 2 MB. Choose a smaller one.");
		}
		try {
			byte[] bytes = file.getBytes();
			String stored = kind.folder() + "/" + UUID.randomUUID() + "." + extension(bytes);
			Path target = root.resolve(stored);
			Files.createDirectories(target.getParent());
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
			return stored;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not save an uploaded image", ex);
		}
	}

	/**
	 * Removes a stored image, for example after it was replaced. Null, missing files and paths that point outside the
	 * upload directory (or are not valid paths at all) are ignored.
	 */
	public void delete(String storedPath) {
		if (storedPath == null || storedPath.isBlank()) {
			return;
		}
		Path target;
		try {
			target = root.resolve(storedPath).normalize();
		}
		catch (InvalidPathException ex) { // not a path this file system can hold, so nothing of ours to delete
			return;
		}
		if (!target.startsWith(root) || target.equals(root)) {
			return;
		}
		try {
			Files.deleteIfExists(target);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not delete an uploaded image", ex);
		}
	}

	/** File extension for the detected image type. */
	static String extension(byte[] bytes) {
		if (startsWith(bytes, 0, JPEG)) {
			return "jpg";
		}
		if (startsWith(bytes, 0, PNG)) {
			return "png";
		}
		if (startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP)) {
			return "webp";
		}
		throw new InvalidImageException("Choose a JPG, PNG or WebP image.");
	}

	private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
		return bytes.length >= offset + prefix.length
				&& Arrays.equals(bytes, offset, offset + prefix.length, prefix, 0, prefix.length);
	}

}
