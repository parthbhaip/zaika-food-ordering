package com.zaika.delivery;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryPartnerProfileRepository extends JpaRepository<DeliveryPartnerProfile, Long> {

	/** The rider's profile with their account, for the rider panel. */
	@EntityGraph(attributePaths = "user")
	Optional<DeliveryPartnerProfile> findByUserId(Long userId);

	boolean existsByUserId(Long userId);

	/** Uses {@code idx_delivery_partner_profiles_status}. */
	long countByStatus(RiderStatus status);

	/** Uses {@code idx_delivery_partner_profiles_status}. */
	long countByStatusAndOnline(RiderStatus status, boolean online);

	/** The rider's profile with a row lock: one rider's claims (two tabs, a double tap) run one after the other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from DeliveryPartnerProfile p where p.user.id = :userId")
	Optional<DeliveryPartnerProfile> findForUpdateByUserId(long userId);

	/**
	 * The admin rider list, newest sign-ups first, accounts loaded: riders in {@code status} (any when null) whose
	 * name, email, preferred area or number plate is LIKE {@code pattern} (lower-cased, {@code !} escapes; any when
	 * null). Uses {@code idx_delivery_partner_profiles_status_created} / {@code idx_delivery_partner_profiles_created}.
	 */
	@Query(value = """
			select p from DeliveryPartnerProfile p join fetch p.user u
			where (:status is null or p.status = :status)
				and (:pattern is null or lower(u.name) like :pattern escape '!' or lower(u.email) like :pattern escape '!'
					or lower(p.preferredArea) like :pattern escape '!' or lower(p.vehicleNumber) like :pattern escape '!')
			order by p.createdAt desc, p.id desc""", countQuery = """
			select count(p) from DeliveryPartnerProfile p join p.user u
			where (:status is null or p.status = :status)
				and (:pattern is null or lower(u.name) like :pattern escape '!' or lower(u.email) like :pattern escape '!'
					or lower(p.preferredArea) like :pattern escape '!' or lower(p.vehicleNumber) like :pattern escape '!')""")
	Page<DeliveryPartnerProfile> findForModeration(RiderStatus status, String pattern, Pageable pageable);

	/** How many riders are in each status (statuses with none are missing). */
	record StatusCount(RiderStatus status, Long riders) {
	}

	@Query("select new com.zaika.delivery.DeliveryPartnerProfileRepository$StatusCount(p.status, count(p)) from DeliveryPartnerProfile p group by p.status")
	List<StatusCount> countPerStatus();

}
