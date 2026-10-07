package com.zaika.owner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A menu category's name ("Starters", "Thalis"), for adding and renaming one. */
public class CategoryForm {

	@NotBlank(message = "Give the category a name, like Starters.")
	@Size(max = 60, message = "Keep the name under 60 characters.")
	@Pattern(regexp = ".*\\p{Alnum}.*", message = "Use letters or digits in the name.")
	private String name;

	public CategoryForm() {
	}

	CategoryForm(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name == null || name.isBlank() ? null : name.strip();
	}

}
