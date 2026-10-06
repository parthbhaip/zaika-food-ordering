package com.zaika.payment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** The latest attempt for the order (idx_payments_order). */
	Optional<Payment> findFirstByOrderIdOrderByIdDesc(long orderId);

}
