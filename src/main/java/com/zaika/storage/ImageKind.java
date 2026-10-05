package com.zaika.storage;

import java.util.Locale;

/** What an uploaded image is for. Each kind gets its own folder under {@code app.upload-dir}: logo/, cover/, dish/. */
public enum ImageKind {

	LOGO, COVER, DISH;

	public String folder() {
		return name().toLowerCase(Locale.ROOT);
	}

}
