package com.zaika.review;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<Review, Long> {

	/** The review of an order, if it has one (uk_reviews_order). */
	Optional<Review> findByOrderId(long orderId);

	boolean existsByOrderId(long orderId);

	Optional<Review> findByIdAndRestaurantOwnerId(long id, long ownerId);

	/**
	 * A page of a restaurant's public reviews (not hidden), customers loaded, in the pageable's sort
	 * (idx_reviews_restaurant_created / idx_reviews_restaurant_stars).
	 */
	@Query(value = "select r from Review r join fetch r.customer where r.restaurant.id = :restaurantId and r.hidden = false",
			countQuery = "select count(r) from Review r where r.restaurant.id = :restaurantId and r.hidden = false")
	Page<Review> findVisible(long restaurantId, Pageable pageable);

	/** The newest public reviews of a restaurant, customers loaded: the restaurant page's preview. */
	@Query("""
			select r from Review r join fetch r.customer where r.restaurant.id = :restaurantId and r.hidden = false
			order by r.createdAt desc, r.id desc""")
	List<Review> findLatest(long restaurantId, Limit limit);

	/** A page of every review of a restaurant, hidden ones too, newest first, customers and orders loaded: the owner's list. */
	@Query(value = """
			select r from Review r join fetch r.customer join fetch r.order
			where r.restaurant.id = :restaurantId order by r.createdAt desc, r.id desc""",
			countQuery = "select count(r) from Review r where r.restaurant.id = :restaurantId")
	Page<Review> findForOwner(long restaurantId, Pageable pageable);

	/** Shown reviews the owner has not answered yet. */
	long countByRestaurantIdAndHiddenFalseAndReplyIsNull(long restaurantId);

	/** The owner's review, row-locked so a reply is written once; another restaurant's review finds nothing. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.id = :id and r.restaurant.owner.id = :ownerId")
	Optional<Review> findForReply(long id, long ownerId);

	/** Any review, row-locked: an admin hiding or showing it. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.id = :id")
	Optional<Review> findForUpdate(long id);

	/**
	 * The admin list, newest first, restaurant, customer and order loaded: reviews hidden or not ({@code hidden}, any
	 * when null) whose restaurant name, customer name or comment is LIKE {@code pattern} (lower-cased, {@code !}
	 * escapes; any when null).
	 */
	@Query(value = """
			select r from Review r join fetch r.restaurant t join fetch r.customer c join fetch r.order
			where (:hidden is null or r.hidden = :hidden)
				and (:pattern is null or lower(t.name) like :pattern escape '!' or lower(c.name) like :pattern escape '!'
					or lower(r.comment) like :pattern escape '!')
			order by r.createdAt desc, r.id desc""", countQuery = """
			select count(r) from Review r join r.restaurant t join r.customer c
			where (:hidden is null or r.hidden = :hidden)
				and (:pattern is null or lower(t.name) like :pattern escape '!' or lower(c.name) like :pattern escape '!'
					or lower(r.comment) like :pattern escape '!')""")
	Page<Review> findForAdmin(Boolean hidden, String pattern, Pageable pageable);

	/** How many reviews are hidden and how many shown. */
	@Query("select new com.zaika.review.ReviewRepository$VisibilityCount(r.hidden, count(r)) from Review r group by r.hidden")
	List<VisibilityCount> countPerVisibility();

	record VisibilityCount(boolean hidden, long count) {
	}

}
