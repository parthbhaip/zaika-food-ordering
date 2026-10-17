package com.zaika.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A cuisine's name ("Konkani", "Street Food"), for adding and renaming one. */
public class CuisineForm {

	@NotBlank(message = "Name the cuisine, like Konkani.")
	@Size(max = 40, message = "Keep the name under 40 characters.")
	@Pattern(regexp = ".*[A-Za-z0-9].*", message = "Use letters or digits in the name.")
	private String name;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name == null || name.isBlank() ? null : name.strip().replaceAll("\s+", " ");
	}

}
