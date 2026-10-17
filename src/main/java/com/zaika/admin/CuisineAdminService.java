package com.zaika.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.CuisineRepository;
import com.zaika.catalog.CuisineRepository.Usage;
import com.zaika.catalog.Slugs;
import com.zaika.common.NotFoundException;

/**
 * Cuisines for the admin: the list with how many restaurants use each, add, rename and delete. A cuisine's slug is
 * made from its first name and never changes (it is in shared links like {@code /restaurants?cuisine=konkani}), so a
 * new name must not clash with any existing name or slug. A cuisine a restaurant still lists cannot be deleted.
 */
@Service
@Transactional
public class CuisineAdminService {

	private final CuisineRepository cuisines;

	CuisineAdminService(CuisineRepository cuisines) {
		this.cuisines = cuisines;
	}

	/** Every cuisine by name with its restaurant counts (2 statements). */
	@Transactional(readOnly = true)
	public List<Row> list() {
		Map<Long, Usage> usage = cuisines.usage().stream()
			.collect(Collectors.toMap(Usage::cuisineId, Function.identity()));
		return cuisines.findAllByOrderByNameAsc().stream().map(c -> {
			Usage u = usage.get(c.getId());
			return new Row(c, u == null ? 0 : u.restaurants(), u == null ? 0 : u.live());
		}).toList();
	}

	/** Adds a cuisine from a valid name; returns the stored name. */
	public String create(String name) {
		String slug = Slugs.slugify(name);
		cuisines.findByNameIgnoreCase(name).ifPresent(c -> {
			throw new FieldRuleException("name", "There is already a cuisine called " + c.getName() + ".");
		});
		cuisines.findBySlug(slug).ifPresent(c -> {
			throw new FieldRuleException("name",
					"That name is too close to " + c.getName() + ". Rename " + c.getName() + " instead, or pick another name.");
		});
		return cuisines.saveAndFlush(new Cuisine(name)).getName();
	}

	/** Renames a cuisine (its slug stays); returns the old name. */
	public String rename(long id, String name) {
		Cuisine cuisine = cuisines.findById(id).orElseThrow(() -> new NotFoundException("No cuisine " + id));
		cuisines.findByNameIgnoreCase(name).filter(other -> !other.getId().equals(id)).ifPresent(c -> {
			throw new FieldRuleException("name", "There is already a cuisine called " + c.getName() + ".");
		});
		String before = cuisine.getName();
		cuisine.rename(name);
		cuisines.flush();
		return before;
	}

	/** Deletes a cuisine no restaurant lists; returns its name. */
	public String delete(long id) {
		Cuisine cuisine = cuisines.findById(id).orElseThrow(() -> new NotFoundException("No cuisine " + id));
		long using = cuisines.usage().stream().filter(u -> u.cuisineId() == id).mapToLong(Usage::restaurants).sum();
		if (using > 0) {
			throw new ModerationException(cuisine.getName() + " is listed by "
					+ (using == 1 ? "one restaurant" : using + " restaurants")
					+ ". It can only be deleted once no restaurant lists it. Rename it instead.");
		}
		cuisines.delete(cuisine);
		cuisines.flush(); // a restaurant that picked it meanwhile fails here, inside the transaction
		return cuisine.getName();
	}

	/** A cuisine with how many restaurants list it, and how many of those are live. */
	public record Row(Cuisine cuisine, long restaurants, long live) {
	}

}
