package com.zaika.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

	/** The restaurant page lookup, cuisines included (one query). */
	@EntityGraph(attributePaths = "cuisines")
	Optional<Restaurant> findBySlug(String slug);

	/** The owner's own restaurant (an owner has at most one). */
	Optional<Restaurant> findByOwnerId(Long ownerId);

	/**
	 * Loads the cuisines of a page of restaurants in one query. Called inside the transaction that loaded the page,
	 * it fills the cuisine collections of those same managed instances (a fetch join cannot be paged in SQL).
	 */
	@EntityGraph(attributePaths = "cuisines")
	List<Restaurant> findWithCuisinesByIdIn(Collection<Long> ids);

	/**
	 * Header search suggestions: restaurants in {@code status} whose name or one of whose cuisines is LIKE
	 * {@code pattern} (lower-cased, {@code !} escapes). Name matches first, then the best rated.
	 */
	@Query("""
			select r from Restaurant r
			where r.status = :status and (lower(r.name) like :pattern escape '!' or exists (
				select c.id from Restaurant t join t.cuisines c where t = r and lower(c.name) like :pattern escape '!'))
			order by case when lower(r.name) like :pattern escape '!' then 0 else 1 end,
				r.ratingAverage desc, r.ratingCount desc, r.id""")
	List<Restaurant> suggest(RestaurantStatus status, String pattern, Limit limit);

}
