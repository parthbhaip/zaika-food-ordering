package com.zaika.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.zaika.common.BaseEntity;

/** A cuisine restaurants are tagged with ("South Indian"), linked from the home page chips as {@code ?cuisine=slug}. */
@Entity
@Table(name = "cuisines")
public class Cuisine extends BaseEntity {

	@NotBlank
	@Size(max = 40)
	@Column(nullable = false, length = 40, unique = true)
	private String name;

	@Pattern(regexp = Slugs.PATTERN)
	@Size(max = 40)
	@Column(nullable = false, length = 40, unique = true)
	private String slug;

	protected Cuisine() {
	}

	/** The slug is derived from the name and never changes afterwards (it is used in links). */
	public Cuisine(String name) {
		this.name = name.strip();
		this.slug = Slugs.slugify(name);
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

}
