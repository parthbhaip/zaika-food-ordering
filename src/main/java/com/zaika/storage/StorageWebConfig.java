package com.zaika.storage;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves stored uploads at {@code /uploads/**}. Names are random and a file is never rewritten, so browsers may keep
 * each one for a year without asking again.
 */
@Configuration(proxyBeanMethods = false)
class StorageWebConfig implements WebMvcConfigurer {

	private final StorageService storage;

	StorageWebConfig(StorageService storage) {
		this.storage = storage;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = storage.root().toUri().toString();
		registry.addResourceHandler(StorageService.URL_PREFIX + "**")
			.addResourceLocations(location.endsWith("/") ? location : location + "/")
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
	}

}
