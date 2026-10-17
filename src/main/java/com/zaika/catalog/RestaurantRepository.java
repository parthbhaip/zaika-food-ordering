package com.zaika.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long>, JpaSpecificationExecutor<Restaurant> {

	/** The restaurant page lookup, cuisines included (one query). */
	@EntityGraph(attributePaths = "cuisines")
	Optional<Restaurant> findBySlug(String slug);

	/** Uses {@code idx_restaurants_status}. */
	long countByStatus(RestaurantStatus status);

	/** The owner's own restaurant (an owner has at most one). */
	Optional<Restaurant> findByOwnerId(Long ownerId);

	/** The owner's own restaurant with its cuisines (one query), for the owner panel. */
	@EntityGraph(attributePaths = "cuisines")
	Optional<Restaurant> findWithCuisinesByOwnerId(Long ownerId);

	boolean existsBySlug(String slug);

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

	/** The admin review page: the restaurant with its owner account and cuisines (one query). */
	@EntityGraph(attributePaths = { "owner", "cuisines" })
	Optional<Restaurant> findWithOwnerBySlug(String slug);

	/** Locks the row, so two moderation actions on one restaurant run one after the other. Owner loaded. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = "owner")
	Optional<Restaurant> findForUpdateBySlug(String slug);

	/**
	 * The admin moderation list, newest sign-ups first, owners loaded: restaurants in {@code status} (any when null)
	 * whose name, slug, area, owner name or owner email is LIKE {@code pattern} (lower-cased, {@code !} escapes; any
	 * when null). Uses {@code idx_restaurants_status_created} / {@code idx_restaurants_created}.
	 */
	@Query(value = """
			select r from Restaurant r join fetch r.owner o
			where (:status is null or r.status = :status)
				and (:pattern is null or lower(r.name) like :pattern escape '!' or lower(r.slug) like :pattern escape '!'
					or lower(r.area) like :pattern escape '!' or lower(o.name) like :pattern escape '!'
					or lower(o.email) like :pattern escape '!')
			order by r.createdAt desc, r.id desc""", countQuery = """
			select count(r) from Restaurant r join r.owner o
			where (:status is null or r.status = :status)
				and (:pattern is null or lower(r.name) like :pattern escape '!' or lower(r.slug) like :pattern escape '!'
					or lower(r.area) like :pattern escape '!' or lower(o.name) like :pattern escape '!'
					or lower(o.email) like :pattern escape '!')""")
	Page<Restaurant> findForModeration(RestaurantStatus status, String pattern, Pageable pageable);

	/** How many restaurants are in each status (statuses with none are missing). */
	record StatusCount(RestaurantStatus status, Long restaurants) {
	}

	@Query("select new com.zaika.catalog.RestaurantRepository$StatusCount(r.status, count(r)) from Restaurant r group by r.status")
	List<StatusCount> countPerStatus();

}
